package com.unihub.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.payment.infrastructure.out.client.StripeProperties;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * The ConfigMap and the Secret are made of environment variables, and Spring maps an environment
 * variable to a property by its own rule: {@code _} becomes {@code .} and a {@code -} in the
 * property name is dropped. A name that looks right can therefore bind to nothing and the
 * application still starts (Phase 7 checklist C46, D87). {@code StripeProperties}' own Javadoc says
 * {@code UNIHUB_PAYMENT_STRIPE_SECRET_KEY}, which would read the property {@code secret.key};
 * {@code secret-key} is {@code UNIHUB_PAYMENT_STRIPE_SECRETKEY}. This test hands the real manifest
 * names to Spring's own binder, so the trap is a red test, not a pod that quietly has no gateway.
 */
class EnvironmentVariableBindingTest {

    private static final Path K8S = Path.of("..", "..", "k8s", "base");

    @Test
    @DisplayName(
            "the manifest's Stripe variables bind: the key reaches the properties, the base is the stub")
    void stripeVariablesBind() throws IOException {
        var binder = binderOverManifestVariables();

        var stripe = binder.bind("unihub.payment.stripe", StripeProperties.class).get();

        assertThat(stripe.secretKey())
                .as("UNIHUB_PAYMENT_STRIPE_SECRETKEY must reach unihub.payment.stripe.secret-key")
                .isEqualTo("secret-key-from-the-template");
        assertThat(stripe.apiBase())
                .as("UNIHUB_PAYMENT_STRIPE_APIBASE is the in-cluster stub, not api.stripe.com")
                .isEqualTo("http://payment-gateway-stub:8080");
    }

    @Test
    @DisplayName(
            "the variables of the datasources, Redis, SMTP and the object store bind to their properties")
    void plainVariablesBind() throws IOException {
        var binder = binderOverManifestVariables();

        assertThat(binder.bind("spring.datasource.url", String.class).get())
                .isEqualTo("jdbc:postgresql://postgres-master:5432/unihub");
        assertThat(binder.bind("unihub.datasource.replica.url", String.class).get())
                .isEqualTo("jdbc:postgresql://postgres-replica:5432/unihub");
        assertThat(binder.bind("unihub.redis.address", String.class).get())
                .isEqualTo("redis://redis:6379");
        assertThat(binder.bind("spring.rabbitmq.host", String.class).get()).isEqualTo("rabbitmq");
        assertThat(binder.bind("unihub.notification.smtp.host", String.class).get())
                .isEqualTo("mailpit");
        assertThat(binder.bind("unihub.object-storage.endpoint", String.class).get())
                .isEqualTo("http://minio:9000");
        assertThat(binder.bind("unihub.object-storage.create-bucket", Boolean.class).get())
                .isTrue();
        assertThat(binder.bind("unihub.ai-summarization.base-url", String.class).get())
                .isEqualTo("http://ai-summarization-stub:8080");
        assertThat(binder.bind("unihub.security.jwt.secret", String.class).isBound())
                .as("UNIHUB_SECURITY_JWT_SECRET")
                .isTrue();
        assertThat(binder.bind("unihub.ticket.signing-key", String.class).isBound())
                .as("UNIHUB_TICKET_SIGNING_KEY")
                .isTrue();
        assertThat(binder.bind("unihub.dev-seed.student-password", String.class).isBound())
                .as("UNIHUB_DEV_SEED_STUDENT_PASSWORD")
                .isTrue();
        for (var property :
                List.of("organizer-password", "admin-password", "checkin-staff-password")) {
            assertThat(binder.bind("unihub.dev-seed." + property, String.class).isBound())
                    .as("the seeder's %s", property)
                    .isTrue();
        }
    }

    @Test
    @DisplayName(
            "Spring Boot 3.3.5 binds the underscore spelling of a dashed property as well (measured)")
    void theUnderscoreSpellingBindsToo() {
        var variables = new HashMap<String, Object>();
        variables.put("UNIHUB_PAYMENT_STRIPE_SECRET_KEY", "via-underscores");
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addLast(new SystemEnvironmentPropertySource("test", variables));
        ConfigurationPropertySources.attach(environment);

        var bound = Binder.get(environment).bind("unihub.payment.stripe.secret-key", String.class);

        // The first draft of the manifests assumed this did NOT bind. It does, by the legacy
        // mapping in Boot's
        // SystemEnvironmentPropertyMapper, and most of the manifest relies on it: only
        // ...STRIPE_SECRETKEY and
        // ...STRIPE_APIBASE are in the dash-dropped form; UNIHUB_DEV_SEED_*_PASSWORD,
        // UNIHUB_OBJECT_STORAGE_*,
        // UNIHUB_AI_SUMMARIZATION_BASE_URL, UNIHUB_TICKET_SIGNING_KEY and UNIHUB_WEB_BASE_URL bind
        // only this way.
        // plainVariablesBind() pins those names; this test says why they work, and goes red if Boot
        // drops it.
        assertThat(bound.isBound()).isTrue();
    }

    /**
     * A Spring environment whose only source is the manifest's ConfigMap and Secret names, as env
     * vars.
     */
    private static Binder binderOverManifestVariables() throws IOException {
        var variables = new HashMap<String, Object>();
        for (var literal : configMapLiterals()) {
            variables.put(
                    literal.substring(0, literal.indexOf('=')),
                    literal.substring(literal.indexOf('=') + 1));
        }
        // A Secret key has some value at run time; what matters here is only that the NAME binds.
        for (var name : secretKeys()) {
            variables.putIfAbsent(
                    name,
                    name.equals("UNIHUB_PAYMENT_STRIPE_SECRETKEY")
                            ? "secret-key-from-the-template"
                            : "x");
        }

        var environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addLast(
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        sources.addLast(new MapPropertySource("empty", Map.of()));
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment);
    }

    private static List<String> configMapLiterals() throws IOException {
        var result = new ArrayList<String>();
        for (var entry : (List<?>) kustomization().get("configMapGenerator")) {
            var map = (Map<?, ?>) entry;
            if ("backend-api-config".equals(map.get("name"))) {
                for (var literal : (List<?>) map.get("literals")) {
                    result.add((String) literal);
                }
            }
        }
        return result;
    }

    private static List<String> secretKeys() throws IOException {
        try (Reader reader = Files.newBufferedReader(K8S.resolve("secret.example.yaml"))) {
            @SuppressWarnings("unchecked")
            var secret = (Map<String, Object>) new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            var stringData = (Map<String, Object>) secret.get("stringData");
            return new ArrayList<>(stringData.keySet());
        }
    }

    private static Map<?, ?> kustomization() throws IOException {
        try (Reader reader = Files.newBufferedReader(K8S.resolve("kustomization.yaml"))) {
            return (Map<?, ?>) new Yaml().load(reader);
        }
    }
}
