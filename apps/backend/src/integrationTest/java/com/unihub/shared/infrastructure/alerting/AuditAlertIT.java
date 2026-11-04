package com.unihub.shared.infrastructure.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.infrastructure.AuditBroker;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.yaml.snakeyaml.Yaml;

/**
 * M-55, the alert half of INV-14 (Phase 6 checklist D72, 6.6; resilience-testing.md §2.7): a lost
 * audit entry is not only a counter, it is an alert. A real Prometheus scrapes this application's
 * management port every second and a real Grafana, provisioned from the files in {@code
 * observability/} (the very files compose mounts), evaluates the rules; the test stops the audit
 * broker and waits for the audit rule to be {@code Firing}.
 *
 * <p>It also loads <em>every</em> rule and dashboard of the repository into Grafana: one call then
 * says that each file parses, that no rule is in error (each expression ran against a real
 * Prometheus) and that the rule count is the file's. The images are the ones docker-compose pins.
 * The shipped group interval is not shortened for the test.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class AuditAlertIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path OBSERVABILITY = Path.of("..", "..", "observability");
    private static final Path RULES =
            OBSERVABILITY.resolve("grafana/provisioning/alerting/unihub-rules.yml");
    private static final String AUDIT_RULE = "unihub-audit-publish-failures";
    private static final Duration PATIENCE = Duration.ofMinutes(3);

    private static final RabbitMQContainer BROKER =
            new RabbitMQContainer("rabbitmq:3.13.7-management");

    /** Not a literal in the repository: a fresh one for every run of the container. */
    private static final String GRAFANA_ADMIN = "admin";

    private static final String GRAFANA_PASSWORD = UUID.randomUUID().toString();

    private static Network network;
    private static GenericContainer<?> prometheus;
    private static GenericContainer<?> grafana;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    static {
        BROKER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        AuditBroker.register(registry, BROKER);
    }

    @LocalManagementPort private int managementPort;
    @Autowired private AuditTrail trail;

    private void startMonitoring() throws IOException {
        if (grafana != null) {
            return;
        }
        Testcontainers.exposeHostPorts(managementPort);
        network = Network.newNetwork();

        var scrape = Files.createTempFile("prometheus-it", ".yml");
        // A temporary file is private to its owner; the container's user is not that owner.
        Files.setPosixFilePermissions(scrape, PosixFilePermissions.fromString("rw-r--r--"));
        Files.writeString(
                scrape,
                """
                global:
                  scrape_interval: 1s
                  evaluation_interval: 1s
                scrape_configs:
                  - job_name: unihub-backend
                    metrics_path: /actuator/prometheus
                    scrape_interval: 1s
                    static_configs:
                      - targets: ['host.testcontainers.internal:%d']
                """
                        .formatted(managementPort));
        prometheus =
                new GenericContainer<>("prom/prometheus:v3.15.0")
                        .withNetwork(network)
                        .withNetworkAliases("prometheus")
                        .withFileSystemBind(
                                scrape.toString(),
                                "/etc/prometheus/prometheus.yml",
                                BindMode.READ_ONLY)
                        .withExposedPorts(9090)
                        .waitingFor(Wait.forHttp("/-/ready").forPort(9090));
        prometheus.start();

        grafana =
                new GenericContainer<>("grafana/grafana:13.2.4")
                        .withNetwork(network)
                        .withEnv("GF_SECURITY_ADMIN_PASSWORD", GRAFANA_PASSWORD)
                        .withEnv("GF_PLUGINS_PREINSTALL_DISABLED", "true")
                        .withEnv("GF_ANALYTICS_REPORTING_ENABLED", "false")
                        .withEnv("GF_ANALYTICS_CHECK_FOR_UPDATES", "false")
                        // Bind-mounted read-only, as docker-compose mounts them: the same files,
                        // read the
                        // same way.
                        .withFileSystemBind(
                                OBSERVABILITY
                                        .resolve("grafana/provisioning")
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString(),
                                "/etc/grafana/provisioning",
                                BindMode.READ_ONLY)
                        .withFileSystemBind(
                                OBSERVABILITY
                                        .resolve("grafana/dashboards")
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString(),
                                "/var/lib/grafana/dashboards",
                                BindMode.READ_ONLY)
                        .withExposedPorts(3000)
                        .waitingFor(Wait.forHttp("/api/health").forPort(3000));
        grafana.start();
    }

    @AfterAll
    static void stopMonitoring() {
        if (grafana != null) {
            grafana.stop();
        }
        if (prometheus != null) {
            prometheus.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    // ---------- Grafana's API ----------

    private static JsonNode grafana(String path) {
        try {
            var auth =
                    Base64.getEncoder()
                            .encodeToString(
                                    (GRAFANA_ADMIN + ":" + GRAFANA_PASSWORD)
                                            .getBytes(StandardCharsets.UTF_8));
            var response =
                    CLIENT.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://"
                                                            + grafana.getHost()
                                                            + ":"
                                                            + grafana.getMappedPort(3000)
                                                            + path))
                                    .header("Authorization", "Basic " + auth)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Every rule Grafana has loaded, as {@code uid -> rule}. */
    private static Map<String, JsonNode> loadedRules() {
        var rules = new java.util.LinkedHashMap<String, JsonNode>();
        var groups = grafana("/api/prometheus/grafana/api/v1/rules").path("data").path("groups");
        for (var group : groups) {
            for (var rule : group.path("rules")) {
                rules.put(rule.path("uid").asText(), rule);
            }
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static List<String> uidsInFile() throws IOException {
        Map<String, Object> file = new Yaml().load(Files.readString(RULES));
        var uids = new java.util.ArrayList<String>();
        for (var group : (List<Map<String, Object>>) file.get("groups")) {
            for (var rule : (List<Map<String, Object>>) group.get("rules")) {
                uids.add((String) rule.get("uid"));
            }
        }
        return uids;
    }

    // ---------- the evidence ----------

    @Test
    @DisplayName(
            "M-55: every rule and dashboard of observability/ loads into Grafana, every expression"
                    + " runs against Prometheus, and a lost audit entry takes the audit rule to Firing")
    void auditLoss_isAnAlert() throws Exception {
        startMonitoring();
        var expected = uidsInFile();

        try {
            awaitEveryRuleHealthy(expected);
        } catch (RuntimeException | AssertionError e) {
            // The reason is in Grafana's log (a rejected rule file stops its provisioning).
            System.err.println("GRAFANA LOG\n" + grafana.getLogs());
            throw e;
        }
        assertThat(loadedRules().get(AUDIT_RULE).path("state").asText())
                .as("nothing is lost yet")
                .isNotEqualTo("firing");
        awaitDashboardsAndAuditAlert();
    }

    private void awaitEveryRuleHealthy(List<String> expected) {
        await().atMost(PATIENCE)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> {
                            var rules = loadedRules();
                            assertThat(rules.keySet())
                                    .as("rules loaded from unihub-rules.yml")
                                    .containsExactlyInAnyOrderElementsOf(expected);
                            rules.forEach(
                                    (uid, rule) ->
                                            assertThat(rule.path("health").asText())
                                                    .as(
                                                            "health of %s (%s)",
                                                            uid, rule.path("lastError").asText())
                                                    .isEqualTo("ok"));
                            rules.forEach(
                                    (uid, rule) ->
                                            assertThat(rule.path("lastEvaluation").asText())
                                                    .as("%s has been evaluated", uid)
                                                    .doesNotStartWith("0001"));
                        });
    }

    private void awaitDashboardsAndAuditAlert() throws Exception {
        var dashboards = grafana("/api/search?type=dash-db");
        assertThat(dashboards.size())
                .as("dashboards provisioned from observability/grafana/dashboards")
                .isEqualTo(dashboardFiles());

        stopTheAuditBroker();
        try {
            await().atMost(PATIENCE)
                    .pollInterval(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                lose(1);
                                assertThat(loadedRules().get(AUDIT_RULE).path("state").asText())
                                        .as("the audit rule once entries are lost")
                                        .isEqualTo("firing");
                            });
        } catch (RuntimeException | AssertionError e) {
            System.err.println("PROMETHEUS up: " + prometheus("up"));
            System.err.println(
                    "PROMETHEUS failures: " + prometheus("unihub_audit_publish_failures_total"));
            System.err.println(
                    "PROMETHEUS increase: "
                            + prometheus("sum(increase(unihub_audit_publish_failures_total[5m]))"));
            System.err.println("RULE: " + loadedRules().get(AUDIT_RULE));
            throw e;
        }
    }

    private static String prometheus(String query) {
        try {
            return CLIENT.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://"
                                                            + prometheus.getHost()
                                                            + ":"
                                                            + prometheus.getMappedPort(9090)
                                                            + "/api/v1/query?query="
                                                            + java.net.URLEncoder.encode(
                                                                    query, StandardCharsets.UTF_8)))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static int dashboardFiles() throws IOException {
        try (var files = Files.list(OBSERVABILITY.resolve("grafana/dashboards"))) {
            return (int) files.filter(f -> f.toString().endsWith(".json")).count();
        }
    }

    private void stopTheAuditBroker() {
        DockerClientFactory.lazyClient().stopContainerCmd(BROKER.getContainerId()).exec();
    }

    /** Entries the (stopped) broker will not take: each is counted as a lost audit entry. */
    private void lose(int count) {
        for (int i = 0; i < count; i++) {
            trail.record(
                    AuditEntry.of(
                            Actor.system("audit-alert-it"),
                            AuditActions.WORKSHOP_CANCELLED,
                            AuditTargets.WORKSHOP,
                            UUID.randomUUID(),
                            Instant.now()));
        }
    }
}
