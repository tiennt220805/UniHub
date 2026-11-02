package com.unihub.shared.infrastructure.management;

import static com.unihub.shared.security.TestJwts.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * D64 over real HTTP: Actuator lives on its own management port and nowhere else. {@code health}
 * and {@code prometheus} answer without a token there, nothing else is exposed, and the API port
 * serves no {@code /actuator/**} at all (AC64.1). The readiness group asks the Master only, and a
 * Redis that is paused makes the {@code dependencies} group {@code DEGRADED} inside two seconds,
 * with HTTP 200 and without touching readiness (AC64.3). The Redis here is a container of this
 * class's own, so pausing it affects no other test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class ManagementPortIT extends IntegrationTestSupport {

    private static final GenericContainer<?> PRIVATE_REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        PRIVATE_REDIS.start();
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
    }

    @LocalManagementPort private int managementPort;
    @Autowired private TestRestTemplate http;
    @Autowired private ApplicationContext context;
    @Autowired private ObjectMapper json;

    @AfterEach
    void thaw() {
        try {
            DockerClientFactory.lazyClient()
                    .unpauseContainerCmd(PRIVATE_REDIS.getContainerId())
                    .exec();
        } catch (RuntimeException notPaused) {
            // Redis was running already
        }
    }

    private ResponseEntity<String> management(String path) {
        return http.getForEntity("http://localhost:" + managementPort + path, String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody());
    }

    private static Set<String> componentNames(JsonNode health) {
        var names = new java.util.TreeSet<String>();
        health.path("components").fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("health and prometheus answer on the management port without a token")
    void healthAndPrometheusNeedNoToken() {
        assertThat(management("/actuator/health").getStatusCode().value()).isEqualTo(200);
        var prometheus = management("/actuator/prometheus");
        assertThat(prometheus.getStatusCode().value()).isEqualTo(200);
        assertThat(prometheus.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("no other endpoint is exposed on the management port")
    void nothingElseIsExposed() {
        for (var path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/metrics"}) {
            assertThat(management(path).getStatusCode().value()).as(path).isIn(401, 403, 404);
        }
    }

    @Test
    @DisplayName("the API port serves no /actuator path (AC64.1)")
    void apiPortServesNoActuator() {
        for (var path :
                new String[] {
                    "/actuator",
                    "/actuator/health",
                    "/actuator/health/readiness",
                    "/actuator/prometheus"
                }) {
            assertThat(http.getForEntity(path, String.class).getStatusCode().value())
                    .as(path)
                    .isIn(401, 404);
        }
    }

    @Test
    @DisplayName("liveness asks the process only; readiness asks the Master only")
    void groupsAreComposedAsDesigned() throws Exception {
        var liveness = management("/actuator/health/liveness");
        assertThat(liveness.getStatusCode().value()).isEqualTo(200);
        assertThat(body(liveness).path("status").asText()).isEqualTo("UP");

        var readiness = management("/actuator/health/readiness");
        assertThat(readiness.getStatusCode().value()).isEqualTo(200);
        assertThat(componentNames(body(readiness)))
                .containsExactlyInAnyOrder("db", "readinessState");
    }

    @Test
    @DisplayName("the dependencies group reports redis and rabbit")
    void dependenciesGroupHasBothComponents() throws Exception {
        var dependencies = management("/actuator/health/dependencies");
        assertThat(dependencies.getStatusCode().value()).isEqualTo(200);
        assertThat(componentNames(body(dependencies))).containsExactlyInAnyOrder("redis", "rabbit");
        assertThat(body(dependencies).path("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName(
            "a paused Redis is DEGRADED with 200 inside two seconds, readiness untouched (AC64.3)")
    void pausedRedisDegradesTheDependenciesGroupOnly() throws Exception {
        DockerClientFactory.lazyClient().pauseContainerCmd(PRIVATE_REDIS.getContainerId()).exec();

        long started = System.nanoTime();
        var dependencies = management("/actuator/health/dependencies");
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(dependencies.getStatusCode().value()).isEqualTo(200);
        assertThat(body(dependencies).path("status").asText()).isEqualTo("DEGRADED");
        assertThat(body(dependencies).path("components").path("redis").path("status").asText())
                .isEqualTo("DEGRADED");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));

        var readiness = management("/actuator/health/readiness");
        assertThat(readiness.getStatusCode().value()).isEqualTo(200);
        assertThat(body(readiness).path("status").asText()).isEqualTo("UP");

        DockerClientFactory.lazyClient().unpauseContainerCmd(PRIVATE_REDIS.getContainerId()).exec();
        var recovered = false;
        for (int i = 0; i < 20 && !recovered; i++) {
            recovered =
                    "UP"
                            .equals(
                                    body(management("/actuator/health/dependencies"))
                                            .path("status")
                                            .asText());
            if (!recovered) {
                Thread.sleep(250);
            }
        }
        assertThat(recovered).as("redis reported UP again after the unpause").isTrue();
    }

    @Test
    @DisplayName("no OTLP exporter exists unless an endpoint is configured (D80)")
    void noExporterWithoutAnEndpoint() {
        assertThat(context.getBeansOfType(OtlpHttpSpanExporter.class)).isEmpty();
    }
}
