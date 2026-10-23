package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G1a: the pre-flight question to the shared circuit must not use up a probe (checklist 3.5 B1).
 */
class BreakerGatewayAdmissionTest {
    private static final PaymentBreakerProperties SETTINGS =
            new PaymentBreakerProperties(
                    4, 50f, Duration.ofMillis(100), 2, 4, null, null, null, null);

    private final CircuitBreaker breaker =
            CircuitBreakerRegistry.of(ResilientPaymentGateway.breakerConfig(SETTINGS))
                    .circuitBreaker("paymentGateway");
    private final BreakerGatewayAdmission admission = new BreakerGatewayAdmission(breaker);

    @Test
    @DisplayName("a closed circuit admits, and nothing is recorded")
    void closed_admits() {
        assertThat(admission.admitting()).isTrue();

        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("an open circuit does not admit")
    void open_refuses() {
        breaker.transitionToOpenState();

        assertThat(admission.admitting()).isFalse();
    }

    @Test
    @DisplayName("once the open state has run out the question moves it to half-open, and admits")
    void openStateRanOut_admits() throws Exception {
        breaker.transitionToOpenState();
        Thread.sleep(150);

        assertThat(admission.admitting()).isTrue();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("asking over and over in half-open never uses up the probes the calls need")
    void halfOpen_askingLeaksNoProbe() throws Exception {
        breaker.transitionToOpenState();
        Thread.sleep(150);

        for (int i = 0; i < 10; i++) {
            assertThat(admission.admitting()).isTrue();
        }

        // both probe permissions are still there for the calls themselves
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }
}
