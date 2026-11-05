package com.unihub.shared.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.unihub.shared.infrastructure.FixedPorts;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.ratelimit.RedissonRateLimiter;
import com.unihub.shared.security.TestJwts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * The rate limiter fails open, in time, and says so (INV-17, INV-08; distributed-coordination.md
 * §4; Phase 7 checklist 3.4, closing the "rate limiter failing open" deferral of {@code phase-3.md}
 * §5): with Redis stopped and then paused, requests are let through within two seconds, every one
 * is counted in {@code unihub_rate_limit_bypassed_total}, and when Redis is back the limit applies
 * again with no restart. A class of its own on a private Redis, not a probe added to {@code
 * RedisOutageIT}: that test's time bounds have thin margins (M-24 once failed on 0.5 s) and the
 * limiter's extra calls would eat into them.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"unihub.rate-limit.enabled=true", "unihub.rate-limit.failure-cooldown=PT2S"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitOutageIT extends IntegrationTestSupport {

    /** The limiter's command timeout is 0.5 s; the margin is the Docker and scheduling noise. */
    private static final Duration BOUND = Duration.ofSeconds(2);

    private static final int REQUESTS = 12;
    private static final String PROTECTED = "/api/v1/registrations";

    private static final int REDIS_PORT = FixedPorts.free();
    private static final GenericContainer<?> OUTAGE_REDIS =
            new GenericContainer<>("redis:7")
                    .withExposedPorts(6379)
                    .withCreateContainerCmdModifier(
                            cmd ->
                                    cmd.getHostConfig()
                                            .withPortBindings(
                                                    new PortBinding(
                                                            Ports.Binding.bindPort(REDIS_PORT),
                                                            new ExposedPort(6379))));

    static {
        OUTAGE_REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        OUTAGE_REDIS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add(
                "unihub.redis.address",
                () -> "redis://" + OUTAGE_REDIS.getHost() + ":" + REDIS_PORT);
    }

    enum Fault {
        STOPPED {
            @Override
            void inject() {
                DockerClientFactory.instance()
                        .client()
                        .stopContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }

            @Override
            void mend() {
                DockerClientFactory.instance()
                        .client()
                        .startContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }
        },
        PAUSED {
            @Override
            void inject() {
                DockerClientFactory.instance()
                        .client()
                        .pauseContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }

            @Override
            void mend() {
                DockerClientFactory.instance()
                        .client()
                        .unpauseContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }
        };

        abstract void inject();

        abstract void mend();
    }

    @Autowired private MockMvc mvc;
    @Autowired private MeterRegistry meters;

    private double bypassed() {
        double total = 0;
        for (var reason : RedissonRateLimiter.Reason.values()) {
            total +=
                    meters.get(RedissonRateLimiter.BYPASS_COUNTER)
                            .tag("reason", reason.tag())
                            .counter()
                            .count();
        }
        return total;
    }

    private double bypassed(RedissonRateLimiter.Reason reason) {
        return meters.get(RedissonRateLimiter.BYPASS_COUNTER)
                .tag("reason", reason.tag())
                .counter()
                .count();
    }

    private static String freshAddress() {
        var r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    private static MockHttpServletRequestBuilder from(
            MockHttpServletRequestBuilder request, String address) {
        return request.with(
                r -> {
                    r.setRemoteAddr(address);
                    return r;
                });
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder registration(UUID user, String address) {
        return from(
                post(PROTECTED)
                        .header(
                                "Authorization",
                                "Bearer " + TestJwts.valid(user, "REGISTRATION_CREATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                address);
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    @Timeout(300)
    @DisplayName(
            "Redis down: requests pass within 2 s, every one is counted, and the limit returns with Redis")
    void redisDown_failsOpen_countedInTime_thenRecovers(Fault fault) throws Exception {
        var address = freshAddress();
        // the endpoint answers 401 without touching Redis again, so its time is the limiter's time
        assertThat(status(from(get(PROTECTED), address))).isEqualTo(401);
        double before = bypassed();

        fault.inject();
        try {
            for (int i = 1; i <= REQUESTS; i++) {
                long start = System.nanoTime();
                int status = status(from(get(PROTECTED), address));
                var took = Duration.ofNanos(System.nanoTime() - start);

                assertThat(status)
                        .as("request %d is let through, not 429 or 5xx", i)
                        .isEqualTo(401);
                assertThat(took).as("request %d", i).isLessThan(BOUND);
            }

            // each request made exactly one limiter call and each was counted: no silent bypass
            assertThat(bypassed() - before).isEqualTo(REQUESTS);
            if (fault == Fault.PAUSED) {
                assertThat(bypassed(RedissonRateLimiter.Reason.TIMEOUT)).isPositive();
            }
        } finally {
            fault.mend();
        }

        // the limit applies again with no restart: once Redis answers, the 6th attempt is refused
        var user = UUID.randomUUID();
        var recoveredAddress = freshAddress();
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(
                        () -> {
                            double now = bypassed();
                            status(from(get(PROTECTED), recoveredAddress));
                            assertThat(bypassed())
                                    .as("the limiter reaches Redis again")
                                    .isEqualTo(now);
                        });
        for (int i = 1; i <= 5; i++) {
            assertThat(status(registration(user, recoveredAddress))).isNotEqualTo(429);
        }
        assertThat(status(registration(user, recoveredAddress))).isEqualTo(429);
    }
}
