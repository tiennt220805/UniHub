package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCancelRefusedException;
import com.unihub.payment.application.port.out.GatewayCircuitOpenException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayOutcomeUnknownException;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentMethodRejectedException;
import com.unihub.shared.domain.id.PaymentId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared circuit breaker around the gateway (payment.md §2, §3, §5; checklist D12, D17, INV-05)
 * with a stub in place of the gateway: what each refusal and timeout means for each call, which
 * answers move the circuit and which do not, and that one logical call is one data point. Small
 * window and time limits keep it fast; the real defaults are {@code
 * PaymentBreakerPropertiesTest}'s.
 */
class ResilientPaymentGatewayTest {
    private static final Duration LIMIT = Duration.ofMillis(150);
    private static final PaymentBreakerProperties SETTINGS =
            new PaymentBreakerProperties(
                    4,
                    50f,
                    Duration.ofMillis(200),
                    2,
                    4,
                    LIMIT,
                    Duration.ofMillis(300),
                    3,
                    Duration.ofMillis(10));
    private static final PaymentId PAYMENT = PaymentId.newId();

    /** A gateway whose every call runs the behaviour set for it, counting the calls. */
    private static final class Stub implements PaymentGateway {
        final AtomicInteger calls = new AtomicInteger();
        Supplier<Object> behaviour = () -> null;

        @Override
        @SuppressWarnings("unchecked")
        public String create(PaymentId id, BigDecimal amount, String currency, String token) {
            calls.incrementAndGet();
            return (String) behaviour.get();
        }

        @Override
        public ConfirmOutcome confirm(PaymentId id, String gatewayId) {
            calls.incrementAndGet();
            return (ConfirmOutcome) behaviour.get();
        }

        @Override
        public GatewayPaymentState retrieve(String gatewayId) {
            calls.incrementAndGet();
            return (GatewayPaymentState) behaviour.get();
        }

        @Override
        public void cancel(PaymentId id, String gatewayId) {
            calls.incrementAndGet();
            behaviour.get();
        }
    }

    private final Stub stub = new Stub();
    private final CircuitBreaker breaker =
            CircuitBreakerRegistry.of(ResilientPaymentGateway.breakerConfig(SETTINGS))
                    .circuitBreaker("paymentGateway");
    private final ResilientPaymentGateway gateway =
            new ResilientPaymentGateway(stub, breaker, SETTINGS);

    @AfterEach
    void stop() {
        gateway.close();
    }

    private String create() {
        return gateway.create(PAYMENT, new BigDecimal("100000"), "VND", "pm_card_visa");
    }

    private static Supplier<Object> throwing(RuntimeException e) {
        return () -> {
            throw e;
        };
    }

    private static Supplier<Object> sleeping(Duration pause) {
        return () -> {
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
    }

    private void openTheCircuit() {
        stub.behaviour = throwing(new GatewayCallFailedException("down", null));
        for (int i = 0; i < SETTINGS.minimumNumberOfCalls(); i++) {
            assertThatThrownBy(this::create).isInstanceOf(GatewayCallFailedException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        stub.calls.set(0);
    }

    // ---------- passes answers through unchanged ----------

    @Test
    @DisplayName("an answer comes back as it is, from each of the four calls")
    void answers_passThrough() {
        stub.behaviour = () -> "pi_1";
        assertThat(create()).isEqualTo("pi_1");
        stub.behaviour = ConfirmOutcome.Succeeded::new;
        assertThat(gateway.confirm(PAYMENT, "pi_1")).isInstanceOf(ConfirmOutcome.Succeeded.class);
        stub.behaviour = () -> GatewayPaymentState.PROCESSING;
        assertThat(gateway.retrieve("pi_1")).isEqualTo(GatewayPaymentState.PROCESSING);
        stub.behaviour = () -> null;
        gateway.cancel(PAYMENT, "pi_1");
    }

    @Test
    @DisplayName("the gateway's own exceptions arrive as themselves, not wrapped by the decorators")
    void exceptions_arriveUnwrapped() {
        stub.behaviour = throwing(new PaymentMethodRejectedException("no", null));
        assertThatThrownBy(this::create).isExactlyInstanceOf(PaymentMethodRejectedException.class);
        stub.behaviour = throwing(new GatewayCallFailedException("no", null));
        assertThatThrownBy(this::create).isExactlyInstanceOf(GatewayCallFailedException.class);
        stub.behaviour = throwing(new GatewayNotReachedException("no", null));
        assertThatThrownBy(() -> gateway.confirm(PAYMENT, "pi_1"))
                .isExactlyInstanceOf(GatewayNotReachedException.class);
        stub.behaviour = throwing(new GatewayOutcomeUnknownException("no", null));
        assertThatThrownBy(() -> gateway.confirm(PAYMENT, "pi_1"))
                .isExactlyInstanceOf(GatewayOutcomeUnknownException.class);
        stub.behaviour = throwing(new GatewayCancelRefusedException("no", null));
        assertThatThrownBy(() -> gateway.cancel(PAYMENT, "pi_1"))
                .isExactlyInstanceOf(GatewayCancelRefusedException.class);
    }

    // ---------- time limits: what "too slow" means for each call ----------

    @Test
    @DisplayName("a create that takes too long is 'nothing was confirmed', bounded by the limit")
    void create_timeout_isCallFailed() {
        stub.behaviour = sleeping(Duration.ofSeconds(5));
        var started = System.nanoTime();

        assertThatThrownBy(this::create).isExactlyInstanceOf(GatewayCallFailedException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName(
            "a confirm that takes too long MAY HAVE BEEN SENT: outcome unknown, never 'not reached'")
    void confirm_timeout_isOutcomeUnknown() {
        stub.behaviour = sleeping(Duration.ofSeconds(5));

        assertThatThrownBy(() -> gateway.confirm(PAYMENT, "pi_1"))
                .isExactlyInstanceOf(GatewayOutcomeUnknownException.class)
                .isNotInstanceOf(GatewayNotReachedException.class);
    }

    @Test
    @DisplayName(
            "a retrieve and a cancel that take too long are failed calls (retrieve after its retries)")
    void asyncCalls_timeout_areCallFailed() {
        stub.behaviour = sleeping(Duration.ofSeconds(5));

        assertThatThrownBy(() -> gateway.retrieve("pi_1"))
                .isExactlyInstanceOf(GatewayCallFailedException.class);
        assertThat(stub.calls).hasValue(SETTINGS.asyncRetryAttempts());
        stub.calls.set(0);
        assertThatThrownBy(() -> gateway.cancel(PAYMENT, "pi_1"))
                .isExactlyInstanceOf(GatewayCallFailedException.class);
        assertThat(stub.calls).as("a cancel is not retried here").hasValue(1);
    }

    // ---------- the circuit ----------

    @Test
    @DisplayName(
            "enough failures open the circuit; then no call reaches the gateway (AC1.9, AC17.2)")
    void failures_openTheCircuit_andCallsFailFast() {
        openTheCircuit();

        assertThatThrownBy(this::create)
                .isExactlyInstanceOf(GatewayCallFailedException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThatThrownBy(() -> gateway.confirm(PAYMENT, "pi_1"))
                .as("refused before it left: provably not sent")
                .isExactlyInstanceOf(GatewayNotReachedException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThatThrownBy(() -> gateway.retrieve("pi_1"))
                .isExactlyInstanceOf(GatewayCircuitOpenException.class);
        assertThatThrownBy(() -> gateway.cancel(PAYMENT, "pi_1"))
                .isExactlyInstanceOf(GatewayCircuitOpenException.class);

        assertThat(stub.calls).as("the gateway was never reached").hasValue(0);
    }

    @Test
    @DisplayName("the minimum number of calls is honoured: fewer failures never open the circuit")
    void fewerThanMinimumCalls_neverOpen() {
        stub.behaviour = throwing(new GatewayCallFailedException("down", null));

        for (int i = 0; i < SETTINGS.minimumNumberOfCalls() - 1; i++) {
            assertThatThrownBy(this::create).isInstanceOf(GatewayCallFailedException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("create and confirm are two data points of the shared circuit, not one (AC17.2)")
    void createAndConfirm_areTwoDataPoints() {
        stub.behaviour = () -> "pi_1";
        create();
        stub.behaviour = ConfirmOutcome.Succeeded::new;
        gateway.confirm(PAYMENT, "pi_1");

        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("a refused card is an answer, not a sick gateway")
    void declined_isASuccessForTheCircuit() {
        stub.behaviour = () -> new ConfirmOutcome.Declined("card_declined");

        for (int i = 0; i < 10; i++) {
            gateway.confirm(PAYMENT, "pi_1");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("a refused payment method token and a refused cancel never move the circuit")
    void ignoredAnswers_doNotCount() {
        for (int i = 0; i < 10; i++) {
            stub.behaviour = throwing(new PaymentMethodRejectedException("no", null));
            assertThatThrownBy(this::create).isInstanceOf(PaymentMethodRejectedException.class);
            stub.behaviour = throwing(new GatewayCancelRefusedException("no", null));
            assertThatThrownBy(() -> gateway.cancel(PAYMENT, "pi_1"))
                    .isInstanceOf(GatewayCancelRefusedException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    // ---------- retry: only the read ----------

    @Test
    @DisplayName("a retrieve is retried, and the retried call is one data point; a create is not")
    void retry_onlyOnTheRead() {
        var failures = new AtomicInteger(2);
        stub.behaviour =
                () -> {
                    if (failures.getAndDecrement() > 0) {
                        throw new GatewayCallFailedException("blip", null);
                    }
                    return GatewayPaymentState.SUCCEEDED;
                };

        assertThat(gateway.retrieve("pi_1")).isEqualTo(GatewayPaymentState.SUCCEEDED);

        assertThat(stub.calls).hasValue(3);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls())
                .as("one logical call, however many attempts")
                .isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();

        stub.calls.set(0);
        stub.behaviour = throwing(new GatewayCallFailedException("blip", null));
        assertThatThrownBy(this::create).isInstanceOf(GatewayCallFailedException.class);
        assertThat(stub.calls).as("a charge is never retried blindly").hasValue(1);
    }

    // ---------- recovery ----------

    @Test
    @DisplayName("after the open state runs out the circuit probes, and closes again on success")
    void recovers_throughHalfOpen() throws Exception {
        openTheCircuit();
        Thread.sleep(SETTINGS.waitDurationInOpenState().plusMillis(100));
        stub.behaviour = () -> "pi_1";

        assertThat(create()).isEqualTo("pi_1");
        assertThat(create()).isEqualTo("pi_1");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a half-open probe that gets an ignored answer gives its permission back")
    void halfOpen_ignoredAnswer_doesNotLeakAProbe() throws Exception {
        openTheCircuit();
        Thread.sleep(SETTINGS.waitDurationInOpenState().plusMillis(100));
        stub.behaviour = throwing(new PaymentMethodRejectedException("no", null));

        // more than the permitted probes: each ignored answer returned its permission
        for (int i = 0; i < SETTINGS.permittedCallsInHalfOpenState() + 3; i++) {
            assertThatThrownBy(this::create)
                    .isExactlyInstanceOf(PaymentMethodRejectedException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        stub.behaviour = () -> "pi_1";
        assertThat(create()).as("still permitted: the circuit is not wedged").isEqualTo("pi_1");
    }

    @Test
    @DisplayName("a probe that fails reopens the circuit")
    void halfOpen_failure_reopens() throws Exception {
        openTheCircuit();
        Thread.sleep(SETTINGS.waitDurationInOpenState().plusMillis(100));
        stub.behaviour = throwing(new GatewayCallFailedException("still down", null));

        assertThatThrownBy(this::create).isInstanceOf(GatewayCallFailedException.class);
        assertThatThrownBy(this::create).isInstanceOf(GatewayCallFailedException.class);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
