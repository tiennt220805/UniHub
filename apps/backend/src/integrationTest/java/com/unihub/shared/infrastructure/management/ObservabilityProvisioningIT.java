package com.unihub.shared.infrastructure.management;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.AuditBroker;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.yaml.snakeyaml.Yaml;

/**
 * M-57's provisioning half (Phase 6 checklist 6.4, D78, D77; test-matrix §4.5): the dashboards and
 * the alert rules of {@code observability/} refer to nothing that does not exist. Every metric name
 * in a PromQL expression is looked up in a real scrape of the application's management port or of a
 * real RabbitMQ running with the repository's {@code prometheus.conf}; every label value an
 * expression pins ({@code state="open"}, {@code queue="unihub.parking-lot"}) is looked up too,
 * because a wrong value gives a rule that silently never fires. The extraction fails closed: an
 * identifier that is not provably a function, a keyword, a grouping label or a duration is a metric
 * name.
 *
 * <p>It also pins the thresholds of {@code observability.md} §6 (the rule file is the one place
 * they are enforced), and checks the exemplar chain of task 6.3 end to end: the scrape of a sampled
 * request carries a {@code trace_id} exemplar, and the Prometheus datasource links exactly that
 * label to Tempo.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureObservability
class ObservabilityProvisioningIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path OBSERVABILITY = Path.of("..", "..", "observability");
    private static final Path RULES =
            OBSERVABILITY.resolve("grafana/provisioning/alerting/unihub-rules.yml");
    private static final Path DASHBOARDS = OBSERVABILITY.resolve("grafana/dashboards");
    private static final Path DATASOURCES =
            OBSERVABILITY.resolve("grafana/provisioning/datasources/datasources.yml");

    /**
     * Series that exist only after their first event, because a counter with labels is born with
     * the first (channel, result) pair that happens; no rule alerts on their absence. Each entry
     * says why it is not in the scrape.
     */
    private static final Set<String> BORN_WITH_THE_FIRST_EVENT =
            Set.of("unihub_notification_delivery_total");

    /** Series Prometheus itself writes for every target; not in any application's scrape. */
    private static final Set<String> SYNTHETIC = Set.of("up");

    /**
     * Labels Prometheus attaches to every sample of a target; not in the scrape body. A pinned
     * {@code job} is checked against the scrape configurations by {@code pinnedJobsAreScrapeJobs}.
     */
    private static final Set<String> TARGET_LABELS = Set.of("job", "instance");

    private static final RabbitMQContainer BROKER =
            new RabbitMQContainer("rabbitmq:3.13.7-management")
                    .withFileSystemBind(
                            OBSERVABILITY
                                    .resolve("rabbitmq/prometheus.conf")
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString(),
                            "/etc/rabbitmq/conf.d/20-prometheus.conf",
                            BindMode.READ_ONLY)
                    .withExposedPorts(5672, 15672, 15692);

    static {
        BROKER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.redis.address", IntegrationTestSupport::redisAddress);
        AuditBroker.register(registry, BROKER);
    }

    @LocalManagementPort private int managementPort;
    @Autowired private TestRestTemplate http;
    @Autowired private RabbitTemplate rabbit;

    // ---------- what is really exported ----------

    /** name -> label name -> values seen, for the application and the broker together. */
    private record Exported(Map<String, Map<String, Set<String>>> series) {
        boolean has(String name) {
            return series.containsKey(name);
        }
    }

    private static final Pattern SAMPLE =
            Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)\\})?\\s+\\S+.*$");
    private static final Pattern LABEL =
            Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static void absorb(Map<String, Map<String, Set<String>>> into, String body) {
        for (var line : body.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var sample = SAMPLE.matcher(line);
            if (!sample.matches()) {
                continue;
            }
            var labels = into.computeIfAbsent(sample.group(1), k -> new TreeMap<>());
            if (sample.group(2) != null) {
                var label = LABEL.matcher(sample.group(2));
                while (label.find()) {
                    labels.computeIfAbsent(label.group(1), k -> new TreeSet<>())
                            .add(label.group(2));
                }
            }
        }
    }

    private Exported realScrapes() throws Exception {
        rabbit.execute(
                channel -> {
                    // declares nothing new: the application declared its topology on connecting
                    channel.queueDeclarePassive(Topology.PARKING_LOT);
                    return null;
                });
        http.getForEntity("/api/v1/workshops", String.class);
        var series = new TreeMap<String, Map<String, Set<String>>>();
        absorb(
                series,
                http.getForEntity(
                                "http://localhost:" + managementPort + "/actuator/prometheus",
                                String.class)
                        .getBody());
        // the plugin refreshes per-queue series every few seconds
        var client = HttpClient.newHttpClient();
        var broker = new TreeMap<String, Map<String, Set<String>>>();
        for (int i = 0; i < 30 && !broker.containsKey("rabbitmq_queue_messages_ready"); i++) {
            Thread.sleep(1000);
            broker.clear();
            absorb(
                    broker,
                    client.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://"
                                                                    + BROKER.getHost()
                                                                    + ":"
                                                                    + BROKER.getMappedPort(15692)
                                                                    + "/metrics"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body());
        }
        broker.forEach(series::putIfAbsent);
        return new Exported(series);
    }

    // ---------- the files ----------

    private record Rule(
            String uid, String expr, String op, double threshold, String for_, String severity) {}

    @SuppressWarnings("unchecked")
    private static List<Rule> rules() throws IOException {
        Map<String, Object> file = new Yaml().load(Files.readString(RULES));
        var rules = new ArrayList<Rule>();
        for (var group : (List<Map<String, Object>>) file.get("groups")) {
            for (var rule : (List<Map<String, Object>>) group.get("rules")) {
                var data = (List<Map<String, Object>>) rule.get("data");
                var query = (Map<String, Object>) data.get(0).get("model");
                var threshold = (Map<String, Object>) data.get(2).get("model");
                var evaluator =
                        (Map<String, Object>)
                                ((List<Map<String, Object>>) threshold.get("conditions"))
                                        .get(0)
                                        .get("evaluator");
                rules.add(
                        new Rule(
                                (String) rule.get("uid"),
                                (String) query.get("expr"),
                                (String) evaluator.get("type"),
                                ((Number) ((List<Object>) evaluator.get("params")).get(0))
                                        .doubleValue(),
                                (String) rule.get("for"),
                                (String)
                                        ((Map<String, Object>) rule.get("labels"))
                                                .get("severity")));
            }
        }
        return rules;
    }

    /** {@code file name -> every PromQL expression in its panels}. */
    private static Map<String, List<String>> dashboardExpressions() throws IOException {
        var result = new TreeMap<String, List<String>>();
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            for (var file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                var expressions = new ArrayList<String>();
                collect(JSON.readTree(Files.readString(file)), expressions);
                result.put(file.getFileName().toString(), expressions);
            }
        }
        return result;
    }

    private static void collect(JsonNode node, List<String> into) {
        if (node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            e -> {
                                if (e.getKey().equals("expr") && e.getValue().isTextual()) {
                                    into.add(e.getValue().asText());
                                } else {
                                    collect(e.getValue(), into);
                                }
                            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, into));
        }
    }

    // ---------- PromQL, just enough to find the names ----------

    private static final Set<String> KEYWORDS =
            Set.of(
                    "by",
                    "without",
                    "on",
                    "ignoring",
                    "group_left",
                    "group_right",
                    "bool",
                    "and",
                    "or",
                    "unless",
                    "offset",
                    "nan",
                    "inf");

    private static final Pattern GROUPING =
            Pattern.compile("\\b(by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)");

    /** A string literal, escapes included. */
    private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");

    /** Metric names an expression selects: identifiers that are not provably anything else. */
    static Set<String> metricNames(String expression) {
        var text = STRING.matcher(expression).replaceAll("\"\"");
        text = text.replaceAll("\\[[^\\]]*\\]", "");
        text = text.replaceAll("\\{[^}]*\\}", "");
        text = GROUPING.matcher(text).replaceAll(" ");
        var names = new TreeSet<String>();
        var matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            var next = text.substring(matcher.end()).stripLeading();
            if (next.startsWith("(") || KEYWORDS.contains(matcher.group().toLowerCase())) {
                continue;
            }
            names.add(matcher.group());
        }
        return names;
    }

    private static final Pattern SELECTOR =
            Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*\\{([^}]*)\\}");
    private static final Pattern MATCHER =
            Pattern.compile(
                    "([a-zA-Z_][a-zA-Z0-9_]*)\\s*(=~|!~|!=|=)\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    // ---------- the evidence ----------

    @Test
    @DisplayName(
            "every metric and every pinned label value in a dashboard or alert rule exists in a real"
                    + " scrape of the application or of RabbitMQ")
    void everyReferenceExists() throws Exception {
        var exported = realScrapes();
        var expressions = new TreeMap<String, String>();
        for (var rule : rules()) {
            expressions.put("rule " + rule.uid(), rule.expr());
        }
        dashboardExpressions()
                .forEach(
                        (file, list) -> {
                            for (int i = 0; i < list.size(); i++) {
                                expressions.put(file + " #" + i, list.get(i));
                            }
                        });
        assertThat(expressions)
                .as("expressions found in rules and dashboards")
                .hasSizeGreaterThan(40);

        var problems = new ArrayList<String>();
        expressions.forEach(
                (where, expression) -> {
                    var names = metricNames(expression);
                    if (names.isEmpty()) {
                        problems.add(where + ": no metric name found in " + expression);
                    }
                    for (var name : names) {
                        if (SYNTHETIC.contains(name) || BORN_WITH_THE_FIRST_EVENT.contains(name)) {
                            continue;
                        }
                        if (!exported.has(name)) {
                            problems.add(where + ": '" + name + "' is not in any real scrape");
                        }
                    }
                    for (var m : allSelectors(expression)) {
                        if (SYNTHETIC.contains(m.name()) || !exported.has(m.name())) {
                            continue;
                        }
                        checkMatchers(where, m, exported, problems);
                    }
                });

        assertThat(problems).as("references that point at nothing").isEmpty();
    }

    private record Selector(String name, String matchers) {}

    private static List<Selector> allSelectors(String expression) {
        var selectors = new ArrayList<Selector>();
        var matcher = SELECTOR.matcher(expression);
        while (matcher.find()) {
            selectors.add(new Selector(matcher.group(1), matcher.group(2)));
        }
        return selectors;
    }

    private static void checkMatchers(
            String where, Selector selector, Exported exported, List<String> problems) {
        var labels = exported.series().get(selector.name());
        var matcher = MATCHER.matcher(selector.matchers());
        while (matcher.find()) {
            var label = matcher.group(1);
            var operator = matcher.group(2);
            var value = matcher.group(3);
            if (TARGET_LABELS.contains(label)) {
                continue;
            }
            var seen = labels.get(label);
            if (seen == null) {
                problems.add(
                        where
                                + ": '"
                                + selector.name()
                                + "' has no label '"
                                + label
                                + "' in the scrape");
            } else if (operator.equals("=") && !seen.contains(value)) {
                problems.add(
                        where
                                + ": "
                                + selector.name()
                                + "{"
                                + label
                                + "=\""
                                + value
                                + "\"} - the scrape has "
                                + seen);
            }
            // =~ and !~ and != are not checked against values: a status of 5xx may not have
            // happened yet, and a pattern is judged by the rule's own purpose, not by one scrape
        }
    }

    @Test
    @DisplayName("the extraction itself: functions, groupings and durations are not metric names")
    void extractionFindsOnlyMetricNames() {
        assertThat(
                        metricNames(
                                "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))"))
                .containsExactly("http_server_requests_seconds_bucket");
        assertThat(
                        metricNames(
                                "max(jvm_memory_used_bytes{area=\"heap\"} / (jvm_memory_max_bytes{area=\"heap\"} > 0))"))
                .containsExactly("jvm_memory_max_bytes", "jvm_memory_used_bytes");
        assertThat(
                        metricNames(
                                "sum(rate(a_total{status=~\"5..\"}[5m])) / sum(rate(b_total[5m])) > on() 1"))
                .containsExactly("a_total", "b_total");
        assertThat(metricNames("vector(1)")).isEmpty();
        assertThat(metricNames("up{job=\"unihub-backend\"} or on() vector(0)"))
                .containsExactly("up");
        assertThat(metricNames("changes(process_start_time_seconds{job=\"x\"}[10m])"))
                .containsExactly("process_start_time_seconds");
    }

    @Test
    @DisplayName("the rule file carries the thresholds of observability.md section 6")
    void thresholdsAreTheCatalogues() throws Exception {
        record Expected(String uid, String op, double threshold, String for_, String severity) {}
        var expected =
                List.of(
                        new Expected("unihub-audit-publish-failures", "gt", 0, "0s", "warning"),
                        new Expected("unihub-rate-limit-bypassed", "gt", 0, "0s", "warning"),
                        new Expected("unihub-reconciliation-stuck", "gt", 0, "0s", "critical"),
                        new Expected("unihub-refund-parked", "gt", 0, "0s", "critical"),
                        new Expected("unihub-parking-lot", "gt", 0, "0s", "critical"),
                        new Expected("unihub-legacy-sync-quarantine", "gt", 0.05, "0s", "warning"),
                        new Expected("unihub-latency-p95-warning", "gt", 0.5, "5m", "warning"),
                        new Expected("unihub-latency-p95-critical", "gt", 2, "5m", "critical"),
                        new Expected("unihub-5xx-warning", "gt", 0.01, "5m", "warning"),
                        new Expected("unihub-5xx-critical", "gt", 0.05, "5m", "critical"),
                        new Expected("unihub-payment-breaker-open", "gt", 0, "1m", "critical"),
                        new Expected("unihub-queue-lag-warning", "gt", 1000, "5m", "warning"),
                        new Expected("unihub-queue-lag-critical", "gt", 5000, "5m", "critical"),
                        new Expected("unihub-replica-lag", "gt", 10, "5m", "warning"),
                        new Expected("unihub-heap", "gt", 0.85, "5m", "warning"),
                        new Expected(
                                "unihub-refund-required-age-warning", "gt", 3600, "5m", "warning"),
                        new Expected(
                                "unihub-refund-required-age-critical",
                                "gt",
                                86400,
                                "5m",
                                "critical"),
                        new Expected("unihub-seat-hold-lock-unavailable", "gt", 0, "5m", "warning"),
                        new Expected("unihub-outbox-age-warning", "gt", 60, "5m", "warning"),
                        new Expected("unihub-outbox-age-critical", "gt", 300, "5m", "critical"),
                        new Expected("unihub-notification-pending-age", "gt", 300, "5m", "warning"),
                        new Expected("unihub-dependency-down", "lt", 1, "1m", "critical"),
                        new Expected("unihub-rabbitmq-target-down", "lt", 1, "1m", "critical"),
                        new Expected("unihub-app-target-down", "lt", 1, "1m", "critical"),
                        new Expected("unihub-app-restarted", "gt", 0, "0s", "warning"));

        var actual =
                rules().stream()
                        .map(
                                r ->
                                        new Expected(
                                                r.uid(),
                                                r.op(),
                                                r.threshold(),
                                                r.for_(),
                                                r.severity()))
                        .toList();

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        var byUid = new TreeMap<String, String>();
        rules().forEach(r -> byUid.put(r.uid(), r.expr()));
        assertThat(byUid.get("unihub-parking-lot")).contains("unihub.parking-lot");
        assertThat(byUid.get("unihub-reconciliation-stuck"))
                .contains("unihub_payment_reconciliation_stuck_total");
        assertThat(byUid.get("unihub-audit-publish-failures"))
                .contains("unihub_audit_publish_failures_total");
        assertThat(byUid.get("unihub-rate-limit-bypassed"))
                .contains("unihub_rate_limit_bypassed_total");
    }

    @Test
    @DisplayName(
            "every job an alert rule pins is a job of both Prometheus configurations, so renaming"
                    + " one cannot silence a rule")
    @SuppressWarnings("unchecked")
    void pinnedJobsAreScrapeJobs() throws Exception {
        var pinned = new TreeSet<String>();
        for (var rule : rules()) {
            var matcher = JOB_MATCHER.matcher(rule.expr());
            while (matcher.find()) {
                pinned.add(matcher.group(1));
            }
        }
        assertThat(pinned).containsExactlyInAnyOrder("rabbitmq", "unihub-backend");

        for (var file : List.of("prometheus.compose.yml", "prometheus.k8s.yml")) {
            Map<String, Object> config =
                    new Yaml()
                            .load(
                                    Files.readString(
                                            OBSERVABILITY.resolve("prometheus").resolve(file)));
            var jobs = new TreeSet<String>();
            for (var job : (List<Map<String, Object>>) config.get("scrape_configs")) {
                jobs.add((String) job.get("job_name"));
            }
            assertThat(jobs).as("jobs of " + file).containsAll(pinned);
        }
    }

    private static final Pattern JOB_MATCHER = Pattern.compile("job=\"([a-z-]+)\"");

    @Test
    @DisplayName("the target rules read an absent target as 0, and fire only then")
    void absentTargetReadsAsZero() throws Exception {
        var byUid = new TreeMap<String, String>();
        rules().forEach(r -> byUid.put(r.uid(), r.expr()));
        // `or on() vector(0)`: the vector joins only when the target set is empty. A plain
        // `or vector(0)` would always add a 0 and the rule would always fire.
        assertThat(byUid.get("unihub-app-target-down"))
                .isEqualTo("up{job=\"unihub-backend\"} or on() vector(0)");
        // the broker's target vanishes the same way when it is scaled to 0 (pod discovery)
        assertThat(byUid.get("unihub-rabbitmq-target-down"))
                .isEqualTo("up{job=\"rabbitmq\"} or on() vector(0)");
        assertThat(byUid.get("unihub-app-restarted"))
                .isEqualTo("changes(process_start_time_seconds{job=\"unihub-backend\"}[10m])");
    }

    @Test
    @DisplayName(
            "a sampled request leaves a trace_id exemplar in the OpenMetrics scrape, and the"
                    + " Prometheus datasource links that label to Tempo")
    @SuppressWarnings("unchecked")
    void exemplarChain() throws Exception {
        http.getForEntity("/api/v1/workshops", String.class);
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + managementPort
                                                + "/actuator/prometheus"))
                        .header("Accept", "application/openmetrics-text; version=1.0.0")
                        .build();
        var body =
                HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString())
                        .body();

        var exemplar =
                Pattern.compile(
                                "^http_server_requests_seconds_bucket\\{.*\\} \\S+ # \\{(.*?)\\} ",
                                Pattern.MULTILINE)
                        .matcher(body);
        assertThat(exemplar.find())
                .as("an exemplar on a latency bucket of the OpenMetrics scrape")
                .isTrue();
        var label = LABEL.matcher(exemplar.group(1));
        var exemplarLabels = new TreeSet<String>();
        while (label.find()) {
            exemplarLabels.add(label.group(1));
        }
        assertThat(exemplarLabels).contains("trace_id");

        Map<String, Object> file = new Yaml().load(Files.readString(DATASOURCES));
        var prometheus =
                ((List<Map<String, Object>>) file.get("datasources"))
                        .stream()
                                .filter(d -> d.get("uid").equals("prometheus"))
                                .findFirst()
                                .orElseThrow();
        var destinations =
                (List<Map<String, Object>>)
                        ((Map<String, Object>) prometheus.get("jsonData"))
                                .get("exemplarTraceIdDestinations");
        assertThat(destinations).hasSize(1);
        assertThat(destinations.get(0).get("name")).isEqualTo("trace_id");
        assertThat(destinations.get(0).get("datasourceUid")).isEqualTo("tempo");
    }
}
