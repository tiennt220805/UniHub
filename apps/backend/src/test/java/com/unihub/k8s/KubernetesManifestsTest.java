package com.unihub.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Static checks of the Kubernetes manifests (Phase 7 task 5.2, 5.4; D87 AC87.1; C47). They read the
 * files under {@code k8s/} and the application's own configuration, and need neither a cluster nor
 * {@code kubectl}: what a cluster would only reveal as a {@code CreateContainerConfigError}, an
 * unmigrated schema or a pod that does not start, fails here first. That the overlays render is
 * checked by {@code make render} (CI, {@code lint} job); that the pods run is the cluster evidence
 * of task 5.7.
 */
class KubernetesManifestsTest {

    private static final Path REPOSITORY = Path.of("..", "..");
    private static final Path K8S = REPOSITORY.resolve("k8s");
    private static final Path BASE = K8S.resolve("base");
    private static final Path MIGRATIONS =
            REPOSITORY.resolve("apps/backend/src/main/resources/db/migration");

    /**
     * What stops the application from starting, or leaves it unable to do its job, when it is
     * missing: the JWT secret, the Redis address, the SMTP host and the ticket key (all four
     * "required" in {@code env.example}), the Master and Replica datasources, and the broker. Each
     * name is the relaxed-binding form Spring reads (the dash of {@code api-base} is dropped:
     * {@code APIBASE}).
     */
    private static final Set<String> REQUIRED_AT_START =
            Set.of(
                    "UNIHUB_SECURITY_JWT_SECRET",
                    "UNIHUB_REDIS_ADDRESS",
                    "UNIHUB_REDIS_PASSWORD",
                    "UNIHUB_NOTIFICATION_SMTP_HOST",
                    "UNIHUB_TICKET_SIGNING_KEY",
                    "SPRING_DATASOURCE_URL",
                    "SPRING_DATASOURCE_USERNAME",
                    "SPRING_DATASOURCE_PASSWORD",
                    "UNIHUB_DATASOURCE_REPLICA_URL",
                    "UNIHUB_DATASOURCE_REPLICA_USERNAME",
                    "UNIHUB_DATASOURCE_REPLICA_PASSWORD",
                    "SPRING_RABBITMQ_HOST",
                    "SPRING_RABBITMQ_USERNAME",
                    "SPRING_RABBITMQ_PASSWORD");

    private static final String PLACEHOLDER = "<injected at runtime>";

    // ---------- migrations (C47, INV-15) ----------

    @Test
    @DisplayName(
            "the flyway-migrations ConfigMap lists exactly the files of the migration directory")
    void flywayConfigMapListsEveryMigration() throws IOException {
        var listed = new TreeSet<String>();
        for (var file : generator("flyway-migrations").files()) {
            listed.add(Path.of(file).getFileName().toString());
            assertThat(BASE.resolve(file)).as("listed file %s exists", file).exists();
        }

        var onDisk = new TreeSet<String>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .forEach(onDisk::add);
        }

        assertThat(listed)
                .as("a migration missing here is never applied by `make migrate`")
                .isEqualTo(onDisk);
    }

    @Test
    @DisplayName("the Flyway Job mounts the ConfigMap by its fixed name, which has no hash suffix")
    void flywayJobNamesItsConfigMapLiterally() throws IOException {
        var generator = generator("flyway-migrations");
        assertThat(generator.disableNameSuffixHash()).isTrue();

        var job = documents(K8S.resolve("jobs/flyway-migrate-job.yaml")).getFirst();
        assertThat(configMapNamesIn(job)).containsExactly("flyway-migrations");
        assertThat(secretKeysIn(job)).isSubsetOf(secretTemplate().keySet());
    }

    // ---------- configuration (D87, AC87.1) ----------

    @Test
    @DisplayName(
            "every variable the application needs at start-up is in the ConfigMap or the Secret")
    void everyRequiredVariableIsSupplied() throws IOException {
        var supplied = new TreeSet<String>(configMapLiterals().keySet());
        supplied.addAll(secretTemplate().keySet());

        assertThat(supplied).containsAll(REQUIRED_AT_START);
    }

    @Test
    @DisplayName(
            "no name is in both the ConfigMap and the Secret, and the ConfigMap holds no credential")
    void configurationAndSecretsDoNotOverlap() throws IOException {
        var literals = configMapLiterals();

        assertThat(literals.keySet()).doesNotContainAnyElementsOf(secretTemplate().keySet());
        // A credential never goes in a ConfigMap: whatever carries a password, key or secret is a
        // Secret's.
        var credentialLike = Pattern.compile("PASSWORD|SECRET|KEY|TOKEN|CREDENTIAL");
        for (var name : literals.keySet()) {
            assertThat(credentialLike.matcher(name).find())
                    .as("%s looks like a credential but is in the ConfigMap", name)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("secret.example.yaml is outside `resources:` and holds only placeholders")
    void secretTemplateIsNeverApplied() throws IOException {
        assertThat(resources())
                .as("a Secret in `resources:` would be applied with placeholder values")
                .noneMatch(resource -> resource.contains("secret"));

        for (var entry : secretTemplate().entrySet()) {
            // POSTGRES_DB is a name, not a secret: the database is called `unihub`
            // (deployment-and-cicd.md §4).
            if (entry.getKey().equals("POSTGRES_DB")) {
                continue;
            }
            assertThat(String.valueOf(entry.getValue()))
                    .as("value of %s in the template", entry.getKey())
                    .isEqualTo(PLACEHOLDER);
        }
    }

    @Test
    @DisplayName("create-secrets.sh writes exactly the keys of secret.example.yaml")
    void createSecretsScriptMatchesTheTemplate() throws IOException {
        var script = Files.readString(K8S.resolve("scripts/create-secrets.sh"));
        var written = new TreeSet<String>();
        var line = Pattern.compile("echo \"([A-Z][A-Z0-9_]*)=");
        var matcher = line.matcher(script);
        while (matcher.find()) {
            written.add(matcher.group(1));
        }

        assertThat(written).isEqualTo(new TreeSet<>(secretTemplate().keySet()));
    }

    @Test
    @DisplayName("every Secret key and ConfigMap a workload refers to exists")
    void everyReferenceResolves() throws IOException {
        var secretKeys = secretTemplate().keySet();
        var configMaps = new TreeSet<String>();
        generators().forEach(generator -> configMaps.add(generator.name()));

        for (var document : workloads()) {
            var name = name(document);
            assertThat(secretKeysIn(document))
                    .as("secretKeyRef keys of %s", name)
                    .isSubsetOf(secretKeys);
            assertThat(configMapNamesIn(document))
                    .as("ConfigMaps of %s", name)
                    .isSubsetOf(configMaps);
            assertThat(secretNamesIn(document))
                    .as("Secrets of %s", name)
                    .isSubsetOf(Set.of("backend-api-secrets"));
        }
    }

    // ---------- images (D84) ----------

    @Test
    @DisplayName(
            "no image is `latest` or untagged, and ours carry the tag placeholder the Makefile fills in")
    void imagesArePinned() throws IOException {
        var images = new ArrayList<String>();
        for (var document : workloads()) {
            for (var container : containers(document)) {
                images.add((String) container.get("image"));
            }
        }
        assertThat(images).isNotEmpty();

        for (var image : images) {
            assertThat(image).as("image %s", image).doesNotContain(":latest");
            var lastSegment = image.substring(image.lastIndexOf('/') + 1);
            assertThat(lastSegment.contains(":") || image.contains("@sha256:"))
                    .as("image %s has a tag or a digest", image)
                    .isTrue();
            if (image.startsWith("thanhtien2208/")) {
                assertThat(image).endsWith(":@IMAGE_TAG@");
            }
        }
        assertThat(images)
                .contains(
                        "thanhtien2208/unihub-backend:@IMAGE_TAG@",
                        "thanhtien2208/unihub-web:@IMAGE_TAG@");
    }

    // ---------- the backend's Deployment (D89) ----------

    @Test
    @DisplayName(
            "probes use the management port with a written failureThreshold, and the API Service hides it")
    void backendProbesAndService() throws IOException {
        var deployment = document("backend-api-deployment.yaml");
        var container = containers(deployment).getFirst();

        for (var probe : List.of("startupProbe", "readinessProbe", "livenessProbe")) {
            var httpGet = (Map<?, ?>) ((Map<?, ?>) container.get(probe)).get("httpGet");
            assertThat(httpGet.get("port")).as("%s port", probe).isEqualTo("management");
            assertThat(((Map<?, ?>) container.get(probe)).get("failureThreshold"))
                    .as("%s failureThreshold is written down, not defaulted", probe)
                    .isNotNull();
        }
        assertThat(
                        ((Map<?, ?>) ((Map<?, ?>) container.get("readinessProbe")).get("httpGet"))
                                .get("path"))
                .isEqualTo("/actuator/health/readiness");

        var ports =
                ((List<?>)
                                ((Map<?, ?>) document("backend-api-service.yaml").get("spec"))
                                        .get("ports"))
                        .stream().map(port -> (Object) ((Map<?, ?>) port).get("port")).toList();
        assertThat(ports).as("the API Service publishes the API port only").containsExactly(8080);
    }

    @Test
    @DisplayName("the grace period covers the preStop sleep plus Tomcat's graceful-shutdown phase")
    void gracePeriodCoversPreStopAndShutdownPhase() throws IOException {
        var spec = podSpec(document("backend-api-deployment.yaml"));
        var container = containers(document("backend-api-deployment.yaml")).getFirst();
        @SuppressWarnings("unchecked")
        var command = (List<Object>) execCommand(container, "preStop");
        assertThat(command).startsWith("sleep");
        var sleep = Duration.ofSeconds(Long.parseLong((String) command.get(1)));
        var grace =
                Duration.ofSeconds(
                        ((Number) spec.get("terminationGracePeriodSeconds")).longValue());

        var application = new Yaml().<Map<String, Object>>load(readString(applicationYml()));
        assertThat(((Map<?, ?>) application.get("server")).get("shutdown")).isEqualTo("graceful");
        var phase =
                Duration.parse(
                        "PT"
                                + String.valueOf(
                                                ((Map<?, ?>)
                                                                ((Map<?, ?>)
                                                                                application.get(
                                                                                        "spring"))
                                                                        .get("lifecycle"))
                                                        .get("timeout-per-shutdown-phase"))
                                        .toUpperCase());

        assertThat(grace)
                .as(
                        "a pod killed before its shutdown phase ends cuts the requests it was finishing")
                .isGreaterThanOrEqualTo(sleep.plus(phase));
    }

    // ---------- the Makefile and the gateway ----------

    @Test
    @DisplayName("`make rollout` waits for every Deployment and StatefulSet of the base")
    void makefileWaitsForEveryWorkload() throws IOException {
        var makefile = Files.readString(REPOSITORY.resolve("Makefile"));
        var waited = new TreeSet<String>();
        var matcher =
                Pattern.compile("(deployment|statefulset)/([a-z0-9-]+)")
                        .matcher(workloadLines(makefile));
        while (matcher.find()) {
            waited.add(matcher.group(1) + "/" + matcher.group(2));
        }

        var existing = new TreeSet<String>();
        for (var document : workloads()) {
            existing.add(((String) document.get("kind")).toLowerCase() + "/" + name(document));
        }
        assertThat(waited).isEqualTo(existing);
    }

    @Test
    @DisplayName("the gateway sends /api/ to the API and everything else to the Web, on one host")
    void gatewayRoutes() throws IOException {
        var virtualService =
                documents(BASE.resolve("gateway.yaml")).stream()
                        .filter(document -> "VirtualService".equals(document.get("kind")))
                        .findFirst()
                        .orElseThrow();
        var routes = (List<?>) ((Map<?, ?>) virtualService.get("spec")).get("http");

        assertThat(routes).hasSize(2);
        assertThat(destination(routes.get(0))).isEqualTo("backend-api-service:8080");
        assertThat(destination(routes.get(1))).isEqualTo("web:8080");
        assertThat(String.valueOf(routes.get(0))).contains("prefix=/api/");
    }

    // ---------- helpers ----------

    private record Generator(String name, List<String> files, boolean disableNameSuffixHash) {}

    private static List<Generator> generators() throws IOException {
        var kustomization = documents(BASE.resolve("kustomization.yaml")).getFirst();
        var result = new ArrayList<Generator>();
        for (var entry : (List<?>) kustomization.get("configMapGenerator")) {
            var map = (Map<?, ?>) entry;
            var files = new ArrayList<String>();
            if (map.get("files") != null) {
                for (var file : (List<?>) map.get("files")) {
                    var text = (String) file;
                    files.add(text.contains("=") ? text.substring(text.indexOf('=') + 1) : text);
                }
            }
            var options = (Map<?, ?>) map.get("options");
            result.add(
                    new Generator(
                            (String) map.get("name"),
                            files,
                            options != null
                                    && Boolean.TRUE.equals(options.get("disableNameSuffixHash"))));
        }
        return result;
    }

    private static Generator generator(String name) throws IOException {
        return generators().stream()
                .filter(generator -> generator.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no generator named " + name));
    }

    private static Map<String, String> configMapLiterals() throws IOException {
        var kustomization = documents(BASE.resolve("kustomization.yaml")).getFirst();
        var literals = new java.util.TreeMap<String, String>();
        for (var entry : (List<?>) kustomization.get("configMapGenerator")) {
            var map = (Map<?, ?>) entry;
            if ("backend-api-config".equals(map.get("name"))) {
                for (var literal : (List<?>) map.get("literals")) {
                    var text = (String) literal;
                    literals.put(
                            text.substring(0, text.indexOf('=')),
                            text.substring(text.indexOf('=') + 1));
                }
            }
        }
        assertThat(literals).isNotEmpty();
        return literals;
    }

    private static Map<String, Object> secretTemplate() throws IOException {
        @SuppressWarnings("unchecked")
        var stringData = (Map<String, Object>) document("secret.example.yaml").get("stringData");
        return stringData;
    }

    private static List<String> resources() throws IOException {
        var kustomization = documents(BASE.resolve("kustomization.yaml")).getFirst();
        var resources = new ArrayList<String>();
        for (var resource : (List<?>) kustomization.get("resources")) {
            resources.add((String) resource);
        }
        return resources;
    }

    /** Every Deployment and StatefulSet the base lists in {@code resources:}. */
    private static List<Map<String, Object>> workloads() throws IOException {
        var result = new ArrayList<Map<String, Object>>();
        for (var resource : resources()) {
            for (var document : documents(BASE.resolve(resource))) {
                if (Set.of("Deployment", "StatefulSet").contains(document.get("kind"))) {
                    result.add(document);
                }
            }
        }
        return result;
    }

    private static Map<String, Object> document(String file) throws IOException {
        return documents(BASE.resolve(file)).getFirst();
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

    private static String name(Map<String, Object> document) {
        return (String) ((Map<?, ?>) document.get("metadata")).get("name");
    }

    private static Map<?, ?> podSpec(Map<String, Object> workload) {
        var template = (Map<?, ?>) ((Map<?, ?>) workload.get("spec")).get("template");
        return (Map<?, ?>) template.get("spec");
    }

    /** Containers and init containers of a workload (a Job's pod spec is the same shape). */
    private static List<Map<?, ?>> containers(Map<String, Object> workload) {
        var spec = podSpec(workload);
        var result = new ArrayList<Map<?, ?>>();
        for (var key : List.of("initContainers", "containers")) {
            if (spec.get(key) != null) {
                for (var container : (List<?>) spec.get(key)) {
                    result.add((Map<?, ?>) container);
                }
            }
        }
        return result;
    }

    private static Object execCommand(Map<?, ?> container, String hook) {
        var lifecycle = (Map<?, ?>) container.get("lifecycle");
        var handler = (Map<?, ?>) lifecycle.get(hook);
        return ((Map<?, ?>) handler.get("exec")).get("command");
    }

    private static Set<String> secretKeysIn(Map<String, Object> workload) {
        var keys = new TreeSet<String>();
        for (var container : containers(workload)) {
            if (container.get("env") != null) {
                for (var variable : (List<?>) container.get("env")) {
                    var from = ((Map<?, ?>) variable).get("valueFrom");
                    if (from != null && ((Map<?, ?>) from).get("secretKeyRef") != null) {
                        keys.add(
                                (String)
                                        ((Map<?, ?>) ((Map<?, ?>) from).get("secretKeyRef"))
                                                .get("key"));
                    }
                }
            }
        }
        return keys;
    }

    private static Set<String> secretNamesIn(Map<String, Object> workload) {
        var names = new TreeSet<String>();
        for (var container : containers(workload)) {
            if (container.get("env") != null) {
                for (var variable : (List<?>) container.get("env")) {
                    var from = ((Map<?, ?>) variable).get("valueFrom");
                    if (from != null && ((Map<?, ?>) from).get("secretKeyRef") != null) {
                        names.add(
                                (String)
                                        ((Map<?, ?>) ((Map<?, ?>) from).get("secretKeyRef"))
                                                .get("name"));
                    }
                }
            }
            if (container.get("envFrom") != null) {
                for (var source : (List<?>) container.get("envFrom")) {
                    var ref = ((Map<?, ?>) source).get("secretRef");
                    if (ref != null) {
                        names.add((String) ((Map<?, ?>) ref).get("name"));
                    }
                }
            }
        }
        return names;
    }

    private static Set<String> configMapNamesIn(Map<String, Object> workload) {
        var names = new TreeSet<String>();
        for (var container : containers(workload)) {
            if (container.get("envFrom") != null) {
                for (var source : (List<?>) container.get("envFrom")) {
                    var ref = ((Map<?, ?>) source).get("configMapRef");
                    if (ref != null) {
                        names.add((String) ((Map<?, ?>) ref).get("name"));
                    }
                }
            }
        }
        var volumes = podSpec(workload).get("volumes");
        if (volumes != null) {
            for (var volume : (List<?>) volumes) {
                var configMap = ((Map<?, ?>) volume).get("configMap");
                if (configMap != null) {
                    names.add((String) ((Map<?, ?>) configMap).get("name"));
                }
            }
        }
        return names;
    }

    private static String destination(Object route) {
        var routes = (List<?>) ((Map<?, ?>) route).get("route");
        var destination = (Map<?, ?>) ((Map<?, ?>) routes.getFirst()).get("destination");
        return destination.get("host") + ":" + ((Map<?, ?>) destination.get("port")).get("number");
    }

    /** The Makefile's {@code WORKLOADS_*} assignments, continuation lines included. */
    private static String workloadLines(String makefile) {
        var text = new StringBuilder();
        var continued = false;
        for (var line : makefile.split("\n")) {
            if (line.startsWith("WORKLOADS_") || continued) {
                text.append(line).append('\n');
                continued = line.endsWith("\\");
            }
        }
        return text.toString();
    }

    private static Path applicationYml() {
        return REPOSITORY.resolve("apps/backend/src/main/resources/application.yml");
    }

    private static String readString(Path path) throws IOException {
        return Files.readString(path);
    }
}
