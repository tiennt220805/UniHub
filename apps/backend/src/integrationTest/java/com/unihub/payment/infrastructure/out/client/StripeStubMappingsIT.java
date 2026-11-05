package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.yaml.snakeyaml.Yaml;

/**
 * Phase 7 task 5.2 (D86): the payment-gateway stub of the cluster is a file, {@code
 * k8s/base/stubs/payment-gateway/mappings.json}, served by WireMock. This runs the real {@link
 * StripePaymentGateway} against exactly that file, in the image and with the arguments of the
 * committed Deployment, so a new call in the adapter (or an edit of the mappings) that the stub no
 * longer answers fails here instead of in a pod. It is the stub's contract, not a second test of
 * the adapter: the adapter's own behaviour is {@link StripePaymentGatewayIT}'s.
 */
class StripeStubMappingsIT extends IntegrationTestSupport {
    private static final Path REPOSITORY = Path.of("..", "..");
    private static final Path DEPLOYMENT =
            REPOSITORY.resolve("k8s/base/stubs/payment-gateway-stub.yaml");
    private static final Path MAPPINGS =
            REPOSITORY.resolve("k8s/base/stubs/payment-gateway/mappings.json");

    private static GenericContainer<?> stub;

    @BeforeAll
    static void startTheStubAsTheClusterRunsIt() throws IOException {
        var container = wiremockContainerOf(DEPLOYMENT);
        @SuppressWarnings("unchecked")
        var args = (List<String>) container.get("args");
        stub =
                new GenericContainer<>((String) container.get("image"))
                        .withFileSystemBind(
                                MAPPINGS.toAbsolutePath().normalize().toString(),
                                "/home/wiremock/mappings/mappings.json",
                                BindMode.READ_ONLY)
                        .withCommand(args.toArray(String[]::new))
                        .withExposedPorts(8080)
                        .waitingFor(
                                Wait.forHttp("/__admin/health")
                                        .forStatusCode(200)
                                        .withStartupTimeout(Duration.ofSeconds(90)));
        stub.start();
    }

    @AfterAll
    static void stopTheStub() {
        if (stub != null) {
            stub.stop();
        }
    }

    @Test
    @DisplayName(
            "a charge, a read, a refund and a cancel are all answered by the committed mappings")
    void everyCallTheAdapterMakesIsAnswered() {
        var gateway = adapter();
        var payment = PaymentId.newId();

        var intent = gateway.create(payment, new BigDecimal("150000"), "VND", "pm_card_visa");

        // The id comes from the Idempotency-Key the adapter sends, so a retried create answers the
        // same
        // PaymentIntent, as Stripe does for a replayed key.
        assertThat(intent).isEqualTo("pi_" + payment.value());
        assertThat(gateway.create(payment, new BigDecimal("150000"), "VND", "pm_card_visa"))
                .isEqualTo(intent);

        assertThat(gateway.confirm(payment, intent)).isInstanceOf(ConfirmOutcome.Succeeded.class);
        // The reconciliation sweep reads the same status the confirm gave.
        assertThat(gateway.retrieve(intent)).isEqualTo(GatewayPaymentState.SUCCEEDED);

        // List-then-create: no refund exists, so the consumer creates one; the stub echoes the
        // intent.
        assertThat(gateway.findRefund(intent)).isEmpty();
        assertThat(gateway.refund(payment, intent)).isEqualTo(GatewayRefundState.SUCCEEDED);

        gateway.cancel(payment, intent);
    }

    private static PaymentGateway adapter() {
        var base = "http://" + stub.getHost() + ":" + stub.getMappedPort(8080);
        return StripeGatewayFactory.create(
                new StripeProperties(
                        "stub-key-for-tests", base, Duration.ofSeconds(3), Duration.ofSeconds(3)),
                ObservationRegistry.NOOP);
    }

    /**
     * The first container of the stub Deployment: its image and its arguments are what the cluster
     * runs.
     */
    private static Map<?, ?> wiremockContainerOf(Path deployment) throws IOException {
        try (Reader reader = Files.newBufferedReader(deployment)) {
            for (var document : new Yaml().loadAll(reader)) {
                var map = (Map<?, ?>) document;
                if (map != null && "Deployment".equals(map.get("kind"))) {
                    var spec =
                            (Map<?, ?>)
                                    ((Map<?, ?>) ((Map<?, ?>) map.get("spec")).get("template"))
                                            .get("spec");
                    return (Map<?, ?>) ((List<?>) spec.get("containers")).getFirst();
                }
            }
        }
        throw new IllegalStateException("no Deployment in " + deployment);
    }
}
