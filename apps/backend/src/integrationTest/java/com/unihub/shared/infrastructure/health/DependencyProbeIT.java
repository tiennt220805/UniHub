package com.unihub.shared.infrastructure.health;

import static com.unihub.shared.security.TestJwts.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The P1 signal end to end (Phase 6 checklist 4.2, D77, AC77.1): {@code unihub_dependency_up} goes
 * to 0 for a Redis or a RabbitMQ that is <em>paused</em> (sockets open, nothing answered, the case
 * that hangs a naive probe) and back to 1 when it thaws, <b>and a scrape of the management port
 * keeps answering inside two seconds throughout</b>. The second half is the point of a background
 * probe: a health call made while Prometheus scrapes would hold the scrape for as long as the
 * client waits, and the target would go {@code up = 0} with every metric gone. Both dependencies
 * here are containers of this class's own, so pausing them touches no other test.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "unihub.observability.dependency-probe-interval=PT0.5S",
            "unihub.observability.background-refresh=true"
        })
@AutoConfigureObservability
class DependencyProbeIT extends IntegrationTestSupport {
    private static final Duration SCRAPE_BOUND = Duration.ofSeconds(2);
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    private static final GenericContainer<?> PRIVATE_REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);
    private static final RabbitMQContainer PRIVATE_BROKER =
            new RabbitMQContainer("rabbitmq:3.13.7-management");

    static {
        PRIVATE_REDIS.start();
        PRIVATE_BROKER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> SECRET);
        registry.add(
                "unihub.redis.address",
                () ->
                        "redis://"
                                + PRIVATE_REDIS.getHost()
                                + ":"
                                + PRIVATE_REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", PRIVATE_BROKER::getHost);
        registry.add("spring.rabbitmq.port", PRIVATE_BROKER::getAmqpPort);
        registry.add("spring.rabbitmq.username", PRIVATE_BROKER::getAdminUsername);
        registry.add("spring.rabbitmq.password", PRIVATE_BROKER::getAdminPassword);
    }

    @LocalManagementPort private int managementPort;
    @Autowired private TestRestTemplate http;

    @AfterEach
    void thaw() {
        for (var container : new GenericContainer<?>[] {PRIVATE_REDIS, PRIVATE_BROKER}) {
            try {
                DockerClientFactory.lazyClient()
                        .unpauseContainerCmd(container.getContainerId())
                        .exec();
            } catch (RuntimeException notPaused) {
                // it was running
            }
        }
    }

    /** One scrape; fails the test if it took {@link #SCRAPE_BOUND} or longer. */
    private String scrape() {
        var started = System.nanoTime();
        var body =
                http.getForEntity(
                                "http://localhost:" + managementPort + "/actuator/prometheus",
                                String.class)
                        .getBody();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("a scrape never waits on a dependency")
                .isLessThan(SCRAPE_BOUND);
        return body;
    }

    private double up(String dependency) {
        var matcher =
                Pattern.compile(
                                "^unihub_dependency_up\\{dependency=\""
                                        + dependency
                                        + "\"\\}\\s+(\\S+)$",
                                Pattern.MULTILINE)
                        .matcher(scrape());
        assertThat(matcher.find()).as("unihub_dependency_up for %s", dependency).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    private static void pause(GenericContainer<?> container) {
        DockerClientFactory.lazyClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    private static void thaw(GenericContainer<?> container) {
        DockerClientFactory.lazyClient().unpauseContainerCmd(container.getContainerId()).exec();
    }

    @Test
    @DisplayName("a paused Redis is 0 while the scrape keeps answering, and 1 again when it thaws")
    void pausedRedis() {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(up("redis")).isEqualTo(1.0));

        pause(PRIVATE_REDIS);

        await().atMost(PATIENCE)
                .untilAsserted(
                        () -> {
                            assertThat(up("redis")).isZero();
                            assertThat(up("rabbitmq")).isEqualTo(1.0);
                        });

        thaw(PRIVATE_REDIS);

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(up("redis")).isEqualTo(1.0));
    }

    @Test
    @DisplayName(
            "a paused RabbitMQ is 0 while the scrape keeps answering, and 1 again when it thaws")
    void pausedBroker() {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(up("rabbitmq")).isEqualTo(1.0));

        pause(PRIVATE_BROKER);

        await().atMost(PATIENCE)
                .untilAsserted(
                        () -> {
                            assertThat(up("rabbitmq")).isZero();
                            assertThat(up("redis")).isEqualTo(1.0);
                        });

        thaw(PRIVATE_BROKER);

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(up("rabbitmq")).isEqualTo(1.0));
    }
}
