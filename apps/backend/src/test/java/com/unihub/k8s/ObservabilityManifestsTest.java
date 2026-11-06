package com.unihub.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Static checks of the observability stack in the cluster (Phase 7 task 6.1; D95, D99): the
 * manifests of {@code k8s/observability/} against the content they are built from, the compose file
 * they share images with, and the base manifests they scrape. No cluster, no {@code kubectl}: what
 * a cluster would show as a pod that does not start, a target that is never found or a datasource
 * that points nowhere fails here first.
 */
class ObservabilityManifestsTest {

    private static final Path REPOSITORY = Path.of("..", "..");
    private static final Path K8S = REPOSITORY.resolve("k8s");
    private static final Path STACK = K8S.resolve("observability");
    private static final Path BASE = K8S.resolve("base");
    private static final Path CONTENT = REPOSITORY.resolve("observability");
    private static final String NAMESPACE = "unihub-observability";
    private static final String PLACEHOLDER = "<injected at runtime>";

    @Test
    @DisplayName("images are the ones docker-compose runs, pinned, never `latest`")
    void imagesAreComposes() throws IOException {
        var compose = Files.readString(Path.of("docker-compose.yml"));
        var images = new TreeSet<String>();
        for (var document : workloads()) {
            for (var container : containers(document)) {
                images.add((String) container.get("image"));
            }
        }

        assertThat(images)
                .containsExactlyInAnyOrder(
                        "prom/prometheus:v3.15.0",
                        "grafana/tempo:2.10.8",
                        "grafana/grafana:13.2.4");
        for (var image : images) {
            assertThat(compose).as("compose runs %s", image).contains("image: " + image);
        }
    }

    @Test
    @DisplayName(
            "the stack is in its own injected namespace and every object names it, except the Role")
    void namespaceAndInjection() throws IOException {
        var namespace = documents(STACK.resolve("namespace.yaml")).getFirst();
        assertThat(name(namespace)).isEqualTo(NAMESPACE);
        assertThat(labels(namespace)).containsEntry("istio-injection", "enabled");

        var kustomization = documents(STACK.resolve("kustomization.yaml")).getFirst();
        assertThat(kustomization)
                .as("a namespace transformer would move Prometheus's Role out of `unihub`")
                .doesNotContainKey("namespace");

        for (var file : resources()) {
            for (var document : documents(STACK.resolve(file))) {
                var kind = (String) document.get("kind");
                if (kind.equals("Namespace")) {
                    continue;
                }
                var in = namespaceOf(document);
                if (kind.equals("Role") || kind.equals("RoleBinding")) {
                    assertThat(in).as("%s %s", kind, name(document)).isEqualTo("unihub");
                } else if (kind.equals("Telemetry")) {
                    // a selector matches the workloads of its own namespace: the gateway's
                    assertThat(in).as("%s %s", kind, name(document)).isEqualTo("istio-system");
                } else {
                    assertThat(in).as("%s %s", kind, name(document)).isEqualTo(NAMESPACE);
                }
            }
        }
        for (var generator : generators()) {
            assertThat(generator.get("namespace"))
                    .as("ConfigMap %s", generator.get("name"))
                    .isEqualTo(NAMESPACE);
        }
    }

    @Test
    @DisplayName("every ConfigMap a workload mounts is generated, and every generated file exists")
    void configMapsResolve() throws IOException {
        var generated = new TreeSet<String>();
        for (var generator : generators()) {
            generated.add((String) generator.get("name"));
            for (var file : files(generator)) {
                assertThat(STACK.resolve(file)).as("file of %s", generator.get("name")).exists();
            }
        }
        for (var workload : workloads()) {
            var volumes = (List<?>) podSpec(workload).get("volumes");
            for (var volume : volumes) {
                var configMap = ((Map<?, ?>) volume).get("configMap");
                if (configMap != null) {
                    assertThat(generated)
                            .as("ConfigMap of %s", name(workload))
                            .contains((String) ((Map<?, ?>) configMap).get("name"));
                }
            }
        }
    }

    @Test
    @DisplayName("Grafana's dashboards ConfigMap holds exactly the dashboards of observability/")
    void dashboardsAreAllThere() throws IOException {
        var listed = new TreeSet<String>();
        for (var file : files(generator("grafana-dashboards"))) {
            listed.add(Path.of(file).getFileName().toString());
        }
        var onDisk = new TreeSet<String>();
        try (Stream<Path> files = Files.list(CONTENT.resolve("grafana/dashboards"))) {
            files.forEach(file -> onDisk.add(file.getFileName().toString()));
        }

        assertThat(listed).isEqualTo(onDisk);
    }

    @Test
    @DisplayName(
            "grafana-admin: the template is placeholders only, outside `resources:`, and the script writes its keys")
    void grafanaSecret() throws IOException {
        assertThat(resources()).noneMatch(resource -> resource.contains("secret"));

        var template = documents(STACK.resolve("secret.example.yaml")).getFirst();
        @SuppressWarnings("unchecked")
        var stringData = (Map<String, Object>) template.get("stringData");
        assertThat(stringData.values()).containsOnly(PLACEHOLDER);

        var script = Files.readString(K8S.resolve("scripts/create-observability-secret.sh"));
        var written = new TreeSet<String>();
        var matcher = Pattern.compile("echo \"([A-Z][A-Z0-9_]*)=").matcher(script);
        while (matcher.find()) {
            written.add(matcher.group(1));
        }
        assertThat(written).isEqualTo(new TreeSet<>(stringData.keySet()));

        var secretKeys = new TreeSet<String>();
        for (var workload : workloads()) {
            for (var container : containers(workload)) {
                for (var variable : listOf(container.get("env"))) {
                    var from = (Map<?, ?>) ((Map<?, ?>) variable).get("valueFrom");
                    if (from != null && from.get("secretKeyRef") != null) {
                        var ref = (Map<?, ?>) from.get("secretKeyRef");
                        assertThat(ref.get("name")).isEqualTo("grafana-admin");
                        secretKeys.add((String) ref.get("key"));
                    }
                }
            }
        }
        assertThat(stringData.keySet()).containsAll(secretKeys);
    }

    @Test
    @DisplayName("`make deploy-observability` waits for every Deployment of the stack")
    void makefileWaitsForTheStack() throws IOException {
        var makefile = Files.readString(REPOSITORY.resolve("Makefile"));
        var line =
                makefile.lines()
                        .filter(l -> l.startsWith("OBSERVABILITY_WORKLOADS"))
                        .findFirst()
                        .orElseThrow();
        var waited = new TreeSet<String>();
        var matcher = Pattern.compile("deployment/([a-z0-9-]+)").matcher(line);
        while (matcher.find()) {
            waited.add(matcher.group(1));
        }
        var existing = new TreeSet<String>();
        workloads().forEach(document -> existing.add(name(document)));

        assertThat(waited).isEqualTo(existing);
    }

    // ---------- the scrape (D95, AC95.1, AC95.2) ----------

    @Test
    @DisplayName("the scrape finds the pods by label and by a container port that really exists")
    void scrapeTargetsExist() throws IOException {
        var config = scrapeConfig();
        var jobs = new java.util.TreeMap<String, Map<?, ?>>();
        for (var job : listOf(config.get("scrape_configs"))) {
            jobs.put((String) ((Map<?, ?>) job).get("job_name"), (Map<?, ?>) job);
        }
        assertThat(jobs.keySet()).containsExactlyInAnyOrder("rabbitmq", "unihub-backend");

        var expected = Map.of("unihub-backend", "backend-api", "rabbitmq", "rabbitmq");
        var ports = Map.of("unihub-backend", "management", "rabbitmq", "http-prometheus");
        for (var entry : jobs.entrySet()) {
            var job = entry.getValue();
            var keeps = new java.util.TreeMap<String, String>();
            for (var relabel : listOf(job.get("relabel_configs"))) {
                var map = (Map<?, ?>) relabel;
                if ("keep".equals(map.get("action"))) {
                    keeps.put(
                            String.valueOf(((List<?>) map.get("source_labels")).getFirst()),
                            (String) map.get("regex"));
                }
            }
            assertThat(keeps)
                    .as("selection of %s", entry.getKey())
                    .containsEntry("__meta_kubernetes_pod_label_app", expected.get(entry.getKey()))
                    .containsEntry(
                            "__meta_kubernetes_pod_container_port_name", ports.get(entry.getKey()));

            // the label and the port name are in the Deployment that the base applies
            var workload = baseWorkload(expected.get(entry.getKey()));
            assertThat(labelsOfPods(workload)).containsEntry("app", expected.get(entry.getKey()));
            assertThat(portNames(workload)).contains(ports.get(entry.getKey()));

            // discovery reads the namespace the base deploys into, and nothing more
            var discovery = (Map<?, ?>) listOf(job.get("kubernetes_sd_configs")).getFirst();
            assertThat(discovery.get("role")).isEqualTo("pod");
            assertThat(((Map<?, ?>) discovery.get("namespaces")).get("names"))
                    .isEqualTo(List.of("unihub"));
        }
    }

    @Test
    @DisplayName(
            "the scrape is mTLS with the sidecar's own certificates, over HTTP/1.1, and not through Istio's merged endpoint")
    void scrapeIsMutualTls() throws IOException {
        var prometheus = workload("prometheus");
        var annotations = podAnnotations(prometheus);
        var certDirInSidecar = "/etc/istio-output-certs";

        assertThat(annotations.get("proxy.istio.io/config"))
                .contains("OUTPUT_CERTS: " + certDirInSidecar);
        assertThat(annotations.get("sidecar.istio.io/userVolumeMount"))
                .contains("\"name\": \"istio-certs\"")
                .contains(certDirInSidecar);
        // the application's and the broker's scrape ports skip the sidecar: Prometheus speaks TLS
        // itself
        assertThat(annotations.get("traffic.sidecar.istio.io/excludeOutboundPorts").split(","))
                .contains("8081", "15692");
        assertThat(annotations.keySet())
                .as("Istio rewrites prometheus.io/* to its plaintext merged endpoint")
                .noneMatch(key -> key.startsWith("prometheus.io/"));

        // the Prometheus container mounts that volume where the scrape configuration reads it
        var mounts = new TreeSet<String>();
        for (var mount : listOf(containers(prometheus).getFirst().get("volumeMounts"))) {
            if ("istio-certs".equals(((Map<?, ?>) mount).get("name"))) {
                mounts.add((String) ((Map<?, ?>) mount).get("mountPath"));
            }
        }
        assertThat(mounts).hasSize(1);
        var certDir = mounts.first();

        for (var job : listOf(scrapeConfig().get("scrape_configs"))) {
            var map = (Map<?, ?>) job;
            var tls = (Map<?, ?>) map.get("tls_config");
            assertThat(map.get("scheme"))
                    .as("scheme of %s", map.get("job_name"))
                    .isEqualTo("https");
            assertThat(map.get("enable_http2"))
                    .as(
                            "HTTP/2 of %s: the application's port is plain TCP behind its sidecar",
                            map.get("job_name"))
                    .isEqualTo(false);
            assertThat(tls.get("ca_file")).isEqualTo(certDir + "/root-cert.pem");
            assertThat(tls.get("cert_file")).isEqualTo(certDir + "/cert-chain.pem");
            assertThat(tls.get("key_file")).isEqualTo(certDir + "/key.pem");
        }
    }

    @Test
    @DisplayName(
            "no PeerAuthentication has portLevelMtls, and only the namespace default is not STRICT (AC95.2)")
    void noMtlsException() throws IOException {
        var found = 0;
        for (var resource : baseResources()) {
            for (var document : documents(BASE.resolve(resource))) {
                if (!"PeerAuthentication".equals(document.get("kind"))) {
                    continue;
                }
                found++;
                @SuppressWarnings("unchecked")
                var spec = (Map<String, Object>) document.get("spec");
                assertThat(spec).as("%s", name(document)).doesNotContainKey("portLevelMtls");
                var mode = ((Map<?, ?>) spec.get("mtls")).get("mode");
                if (!name(document).equals("default")) {
                    assertThat(mode)
                            .as("%s tightens, it never relaxes", name(document))
                            .isEqualTo("STRICT");
                    assertThat(spec)
                            .as("%s is scoped to a workload", name(document))
                            .containsKey("selector");
                }
            }
        }
        assertThat(found).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName(
            "the workloads Prometheus scrapes are STRICT: under PERMISSIVE an mTLS scrape is handed to the application")
    void scrapedWorkloadsAreStrict() throws IOException {
        var strict = new TreeSet<String>();
        for (var document : documents(BASE.resolve("peer-authentication-scraped.yaml"))) {
            var spec = (Map<?, ?>) document.get("spec");
            var selector = (Map<?, ?>) ((Map<?, ?>) spec.get("selector")).get("matchLabels");
            assertThat(((Map<?, ?>) spec.get("mtls")).get("mode")).isEqualTo("STRICT");
            strict.add((String) selector.get("app"));
        }

        assertThat(strict).containsExactlyInAnyOrder("backend-api", "rabbitmq");
        assertThat(baseResources()).contains("peer-authentication-scraped.yaml");
    }

    @Test
    @DisplayName("Prometheus's Role reads pods of `unihub` and nothing else")
    void prometheusRole() throws IOException {
        var role =
                documents(STACK.resolve("prometheus.yaml")).stream()
                        .filter(d -> "Role".equals(d.get("kind")))
                        .findFirst()
                        .orElseThrow();
        var rules = (List<?>) role.get("rules");

        assertThat(rules).hasSize(1);
        var rule = (Map<?, ?>) rules.getFirst();
        assertThat(rule.get("resources")).isEqualTo(List.of("pods"));
        assertThat(rule.get("verbs")).isEqualTo(List.of("get", "list", "watch"));
        assertThat(namespaceOf(role)).isEqualTo("unihub");
    }

    // ---------- the wires between the pieces ----------

    @Test
    @DisplayName(
            "the application exports to Tempo's Service, and Grafana's datasources are the Services of this namespace")
    void servicesAreWhereTheyAreReferred() throws IOException {
        var services = new java.util.TreeMap<String, Set<Integer>>();
        for (var document : documentsOf(resources())) {
            if ("Service".equals(document.get("kind"))) {
                var ports = new TreeSet<Integer>();
                for (var port : listOf(((Map<?, ?>) document.get("spec")).get("ports"))) {
                    ports.add((Integer) ((Map<?, ?>) port).get("port"));
                }
                services.put(name(document), ports);
            }
        }
        assertThat(services).containsKeys("prometheus", "tempo", "grafana");

        var datasources =
                Files.readString(
                        CONTENT.resolve("grafana/provisioning/datasources/datasources.yml"));
        assertThat(datasources)
                .contains("url: http://prometheus:9090")
                .contains("url: http://tempo:3200");
        assertThat(services.get("prometheus")).contains(9090);
        assertThat(services.get("tempo")).contains(3200, 4318);

        var endpoint =
                Files.readString(BASE.resolve("kustomization.yaml"))
                        .lines()
                        .filter(l -> l.contains("MANAGEMENT_OTLP_TRACING_ENDPOINT="))
                        .findFirst()
                        .orElseThrow()
                        .trim();
        assertThat(endpoint).endsWith("=http://tempo." + NAMESPACE + ":4318/v1/traces");

        var tempo = Files.readString(CONTENT.resolve("tempo/tempo.yaml"));
        assertThat(tempo).contains("endpoint: 0.0.0.0:4318").contains("http_listen_port: 3200");
    }

    // ---------- tracing at the gateway (D98, task 6.3) ----------

    @Test
    @DisplayName(
            "the gateway alone starts traces, sampled at 10%, with a provider Istio's install defines and Tempo receives")
    @SuppressWarnings("unchecked")
    void gatewayTracing() throws IOException {
        var telemetry = documents(STACK.resolve("gateway-tracing.yaml")).getFirst();
        var spec = (Map<String, Object>) telemetry.get("spec");
        var selector = (Map<?, ?>) ((Map<?, ?>) spec.get("selector")).get("matchLabels");
        assertThat(selector).as("without a selector every sidecar would start traces").hasSize(1);
        assertThat(selector.get("istio")).isEqualTo("ingressgateway");
        var gatewayServer =
                (Map<?, ?>)
                        ((Map<?, ?>) documents(BASE.resolve("gateway.yaml")).getFirst().get("spec"))
                                .get("selector");
        assertThat(gatewayServer.get("istio"))
                .as("the Gateway selects the same workload")
                .isEqualTo("ingressgateway");

        var tracing = (Map<?, ?>) ((List<?>) spec.get("tracing")).getFirst();
        assertThat(tracing.get("randomSamplingPercentage")).isEqualTo(10);
        var provider =
                (String) ((Map<?, ?>) ((List<?>) tracing.get("providers")).getFirst()).get("name");

        var install = documents(K8S.resolve("istio/install.yaml")).getFirst();
        var providers =
                (List<Map<String, Object>>)
                        ((Map<String, Object>)
                                        ((Map<String, Object>) install.get("spec"))
                                                .get("meshConfig"))
                                .get("extensionProviders");
        var defined =
                providers.stream()
                        .filter(p -> provider.equals(p.get("name")))
                        .findFirst()
                        .orElseThrow();
        assertThat(defined)
                .as(
                        "W3C traceparent, which the application reads, is what `opentelemetry` propagates")
                .containsKey("opentelemetry");
        var otel = (Map<String, Object>) defined.get("opentelemetry");
        assertThat(otel.get("service")).isEqualTo("tempo." + NAMESPACE + ".svc.cluster.local");

        var tempoPorts = new TreeSet<Integer>();
        for (var document : documentsOf(resources())) {
            if ("Service".equals(document.get("kind")) && "tempo".equals(name(document))) {
                for (var port : listOf(((Map<?, ?>) document.get("spec")).get("ports"))) {
                    tempoPorts.add((Integer) ((Map<?, ?>) port).get("port"));
                }
            }
        }
        assertThat(tempoPorts).contains((Integer) otel.get("port"));
        assertThat(Files.readString(CONTENT.resolve("tempo/tempo.yaml")))
                .contains("endpoint: 0.0.0.0:" + otel.get("port"));
        assertThat(Files.readString(K8S.resolve("scripts/cluster-up.sh")))
                .as("the installation file is what the cluster script applies")
                .contains("install -y -f k8s/istio/install.yaml");
    }

    // ---------- helpers ----------

    private static Map<String, Object> scrapeConfig() throws IOException {
        var generator = generator("prometheus-config");
        assertThat(files(generator)).hasSize(1);
        try (Reader reader = Files.newBufferedReader(STACK.resolve(files(generator).getFirst()))) {
            @SuppressWarnings("unchecked")
            var config = (Map<String, Object>) new Yaml().load(reader);
            return config;
        }
    }

    private static List<Map<String, Object>> generators() throws IOException {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry :
                listOf(
                        documents(STACK.resolve("kustomization.yaml"))
                                .getFirst()
                                .get("configMapGenerator"))) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) entry;
            result.add(map);
        }
        return result;
    }

    private static Map<String, Object> generator(String name) throws IOException {
        return generators().stream()
                .filter(g -> name.equals(g.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no generator named " + name));
    }

    /** The file of each {@code files:} entry, without a {@code key=} prefix. */
    private static List<String> files(Map<String, Object> generator) {
        var result = new ArrayList<String>();
        for (var file : listOf(generator.get("files"))) {
            var text = (String) file;
            result.add(text.contains("=") ? text.substring(text.indexOf('=') + 1) : text);
        }
        return result;
    }

    private static List<String> resources() throws IOException {
        var result = new ArrayList<String>();
        for (var resource :
                listOf(
                        documents(STACK.resolve("kustomization.yaml"))
                                .getFirst()
                                .get("resources"))) {
            result.add((String) resource);
        }
        return result;
    }

    private static List<String> baseResources() throws IOException {
        var result = new ArrayList<String>();
        for (var resource :
                listOf(documents(BASE.resolve("kustomization.yaml")).getFirst().get("resources"))) {
            result.add((String) resource);
        }
        return result;
    }

    private static List<Map<String, Object>> documentsOf(List<String> files) throws IOException {
        var result = new ArrayList<Map<String, Object>>();
        for (var file : files) {
            result.addAll(documents(STACK.resolve(file)));
        }
        return result;
    }

    private static List<Map<String, Object>> workloads() throws IOException {
        return documentsOf(resources()).stream()
                .filter(d -> "Deployment".equals(d.get("kind")))
                .toList();
    }

    private static Map<String, Object> workload(String name) throws IOException {
        return workloads().stream().filter(d -> name.equals(name(d))).findFirst().orElseThrow();
    }

    private static Map<String, Object> baseWorkload(String name) throws IOException {
        for (var resource : baseResources()) {
            for (var document : documents(BASE.resolve(resource))) {
                if (Set.of("Deployment", "StatefulSet").contains(document.get("kind"))
                        && name.equals(name(document))) {
                    return document;
                }
            }
        }
        throw new AssertionError("the base has no workload " + name);
    }

    private static List<Map<String, Object>> documents(Path file) throws IOException {
        var result = new ArrayList<Map<String, Object>>();
        try (Reader reader = Files.newBufferedReader(file)) {
            for (var document : new Yaml().loadAll(reader)) {
                if (document != null) {
                    @SuppressWarnings("unchecked")
                    var map = (Map<String, Object>) document;
                    result.add(map);
                }
            }
        }
        return result;
    }

    private static List<?> listOf(Object value) {
        return value == null ? List.of() : (List<?>) value;
    }

    private static String name(Map<String, Object> document) {
        return (String) ((Map<?, ?>) document.get("metadata")).get("name");
    }

    private static String namespaceOf(Map<String, Object> document) {
        return (String) ((Map<?, ?>) document.get("metadata")).get("namespace");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> labels(Map<String, Object> document) {
        return (Map<String, Object>) ((Map<?, ?>) document.get("metadata")).get("labels");
    }

    private static Map<?, ?> podTemplate(Map<String, Object> workload) {
        return (Map<?, ?>) ((Map<?, ?>) workload.get("spec")).get("template");
    }

    private static Map<?, ?> podSpec(Map<String, Object> workload) {
        return (Map<?, ?>) podTemplate(workload).get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> labelsOfPods(Map<String, Object> workload) {
        return (Map<String, Object>)
                ((Map<?, ?>) podTemplate(workload).get("metadata")).get("labels");
    }

    private static Map<String, String> podAnnotations(Map<String, Object> workload) {
        var result = new java.util.TreeMap<String, String>();
        var annotations =
                (Map<?, ?>) ((Map<?, ?>) podTemplate(workload).get("metadata")).get("annotations");
        if (annotations != null) {
            annotations.forEach((k, v) -> result.put((String) k, String.valueOf(v)));
        }
        return result;
    }

    private static List<Map<?, ?>> containers(Map<String, Object> workload) {
        var result = new ArrayList<Map<?, ?>>();
        for (var key : List.of("initContainers", "containers")) {
            for (var container : listOf(podSpec(workload).get(key))) {
                result.add((Map<?, ?>) container);
            }
        }
        return result;
    }

    private static Set<String> portNames(Map<String, Object> workload) {
        var names = new TreeSet<String>();
        for (var container : containers(workload)) {
            for (var port : listOf(container.get("ports"))) {
                names.add((String) ((Map<?, ?>) port).get("name"));
            }
        }
        return names;
    }
}
