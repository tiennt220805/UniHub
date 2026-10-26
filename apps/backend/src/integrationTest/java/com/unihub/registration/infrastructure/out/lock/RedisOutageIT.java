package com.unihub.registration.infrastructure.out.lock;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.stripe.net.Webhook;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.application.port.in.PurgeIdempotencyKeysUseCase;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.in.scheduling.SchedulerTicks;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * M-34, INV-08 (resilience-testing.md §2.3, scenario 2; checklist D20): the whole application with
 * its Redis stopped (connection refused) and then paused (connections accepted, never answered).
 * Registration is refused with {@code 503} and {@code Retry-After}, and <b>nothing reaches
 * PostgreSQL</b>: no seat taken, no registration, no idempotency record — even though the workshop
 * has plenty of seats, which is exactly why "went ahead uncoordinated" is the failure this test
 * exists to catch. Browsing and the payment webhook carry on; a scheduled tick is skipped without
 * running its job; and once Redis is back the same application registers again with no restart.
 *
 * <p>Runs on a Redis container of its own, never the shared singleton: stopping that one would fail
 * every later class (resilience-testing.md §3). Its host port is fixed so the address survives a
 * stop and start. Asserts outcomes, never which Redisson exception carried the failure. The
 * check-in, reporting and rate-limiter probes of §2.3 are not assertable before Phase 5 and Phase 7
 * (checklist C9).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisOutageIT extends IntegrationTestSupport {
    private static final String WEBHOOK_SECRET = "webhook-secret-for-tests";
    private static final int STUDENTS = 8;

    /** wait 2 s + command timeout 0.5 s, plus the Docker and scheduling margin. */
    private static final Duration BOUND = Duration.ofSeconds(6);

    /** Redisson reconnects with a backoff; the seat lock's lease (5 s) bounds an orphan lock. */
    private static final Duration RECOVERY = Duration.ofSeconds(45);

    /**
     * The tick made during a pause may be executed by Redis when it wakes and then holds the job's
     * key until its lease (1 minute) runs out; the job is skipped meanwhile, which is harmless.
     */
    private static final Duration JOB_RECOVERY = Duration.ofSeconds(60 + 30);

    private static final int REDIS_PORT = freePort();
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

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        registry.add("unihub.payment.stripe.webhook.secret", () -> WEBHOOK_SECRET);
        registry.add("unihub.payment.stripe.secret-key", () -> "sk_test_dummy");
        // the cleanup job is the real job whose tick is observed; it runs only when the test says
        // so
        registry.add("unihub.jobs.idempotency-cleanup.interval", () -> "PT1H");
    }

    /**
     * Counts the runs of the job body in <em>this</em> context. Every other cached context in the
     * JVM also runs the cleanup job against the same database every minute, so a row the job would
     * delete cannot show whether this context's tick ran.
     */
    @TestConfiguration
    static class CountJobRuns {
        static final AtomicInteger PURGES = new AtomicInteger();

        @Bean
        static BeanPostProcessor countPurges() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof PurgeIdempotencyKeysUseCase real) {
                        return (PurgeIdempotencyKeysUseCase)
                                retention -> {
                                    PURGES.incrementAndGet();
                                    return real.purge(retention);
                                };
                    }
                    return bean;
                }
            };
        }
    }

    /** How Redis is broken, and mended. */
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

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;
    @Autowired private ApplicationContext context;

    private RegistrationFixtures fixtures;
    private final ExecutorService threads = Executors.newFixedThreadPool(STUDENTS);

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        threads.shutdownNow();
        fixtures.cleanUp();
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    @Timeout(300)
    @DisplayName(
            "Redis down: registration is refused and writes nothing, the rest carries on, and it recovers")
    void redisDown_registrationRefused_thenRecovers(Fault fault) throws Exception {
        var workshop = aPublishedWorkshop().withCapacity(20).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var workshopId = fixtures.track(workshop.id());
        var students = new ArrayList<UserId>();
        for (int i = 0; i < STUDENTS; i++) {
            students.add(fixtures.aStudent());
        }
        warmUpTheClient();

        fault.inject();
        try {
            // N students at once, seats remaining: every one must be refused
            var responses = registerAllAtOnce(students, workshopId);

            for (var response : responses) {
                assertThat(response.getStatusCode().value()).isEqualTo(503);
                assertThat(response.getHeaders().getFirst("Retry-After")).isNotNull();
            }
            assertThat(fixtures.seatsTaken(workshopId)).as("no seat taken").isZero();
            assertThat(fixtures.registrationRows(workshopId)).as("no registration").isZero();
            assertThat(students)
                    .as("no idempotency record")
                    .allSatisfy(student -> assertThat(fixtures.idempotencyKeys(student)).isZero());

            // the rest of the system is unaffected
            assertThat(http.getForEntity("/api/v1/workshops", String.class).getStatusCode().value())
                    .as("catalog")
                    .isEqualTo(200);
            assertThat(
                            http.getForEntity(
                                            "/api/v1/workshops/" + workshopId.value(), String.class)
                                    .getStatusCode()
                                    .value())
                    .as("detail")
                    .isEqualTo(200);
            assertThat(webhook().getStatusCode().value()).as("payment webhook").isEqualTo(200);

            // a scheduled tick finds no lock and its job body does not run
            int runsBefore = CountJobRuns.PURGES.get();
            SchedulerTicks.idempotencyCleanup(context);
            assertThat(CountJobRuns.PURGES.get())
                    .as("job runs during the outage")
                    .isEqualTo(runsBefore);
        } finally {
            fault.mend();
        }

        // no restart: the same application registers again, within a bounded wait
        var survivor = students.get(0);
        var key = UUID.randomUUID();
        await().atMost(RECOVERY)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> register(survivor, workshopId, key).getStatusCode().value() == 201);
        assertThat(fixtures.seatsTaken(workshopId)).isEqualTo(1);

        // and the job runs again (after the lease of a lock the paused Redis may have taken late)
        int runsBefore = CountJobRuns.PURGES.get();
        await().atMost(JOB_RECOVERY)
                .pollInterval(Duration.ofMillis(500))
                .until(
                        () -> {
                            SchedulerTicks.idempotencyCleanup(context);
                            return CountJobRuns.PURGES.get() > runsBefore;
                        });
    }

    /**
     * The first command connects; the pool must be live for the pause to hang a real socket. Done
     * on a workshop of its own, so the one under test starts without a registration or a lock.
     */
    private void warmUpTheClient() {
        var workshop = aPublishedWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var warmWorkshop = fixtures.track(workshop.id());
        var warm = fixtures.aStudent();
        await().atMost(Duration.ofSeconds(20))
                .until(
                        () ->
                                register(warm, warmWorkshop, UUID.randomUUID())
                                                .getStatusCode()
                                                .value()
                                        == 201);
    }

    private List<ResponseEntity<String>> registerAllAtOnce(
            List<UserId> students, WorkshopId workshop) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<CompletableFuture<ResponseEntity<String>>>();
        for (var student : students) {
            futures.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                awaitLatch(start);
                                var began = System.nanoTime();
                                var response = register(student, workshop, UUID.randomUUID());
                                assertThat(Duration.ofNanos(System.nanoTime() - began))
                                        .as("a request does not hang on the dead Redis")
                                        .isLessThan(BOUND);
                                return response;
                            },
                            threads));
        }
        start.countDown();
        var responses = new ArrayList<ResponseEntity<String>>();
        for (var future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        return responses;
    }

    private ResponseEntity<String> register(UserId student, WorkshopId workshop, UUID key) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student.value(), "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", key.toString());
        var body = "{\"workshopId\":\"" + workshop.value() + "\"}";
        return http.exchange(
                "/api/v1/registrations",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private ResponseEntity<String> webhook() throws Exception {
        var body =
                "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_unknown\"}}}";
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(
                "Stripe-Signature",
                Webhook.Signature.generateSignatureHeader(
                        body, WEBHOOK_SECRET, Instant.now().getEpochSecond()));
        return http.exchange(
                "/api/v1/webhooks/payment-gateway",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
