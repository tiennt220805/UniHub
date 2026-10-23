package com.unihub.payment.infrastructure.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentBreakerPropertiesTest {

    private static PaymentBreakerProperties defaults() {
        return new PaymentBreakerProperties(null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("the defaults are the numbers of payment.md §2, §3 and §5")
    void defaults_areTheDocumentedOnes() {
        var settings = defaults();

        assertThat(settings.windowSize()).isEqualTo(20);
        assertThat(settings.failureRateThreshold()).isEqualTo(50f);
        assertThat(settings.waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.permittedCallsInHalfOpenState()).isEqualTo(5);
        assertThat(settings.minimumNumberOfCalls()).as("D12").isEqualTo(10);
        assertThat(settings.syncTimeLimit()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.asyncTimeLimit()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.asyncRetryAttempts()).as("the first call and 3 retries").isEqualTo(4);
        assertThat(settings.asyncRetryBackoff()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("the breaker built from the defaults is count-based, 20 calls, 50%, 10 minimum")
    void breakerConfig_followsTheDefaults() {
        CircuitBreakerConfig config = ResilientPaymentGateway.breakerConfig(defaults());

        assertThat(config.getSlidingWindowType())
                .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
                .isEqualTo(Duration.ofSeconds(30).toMillis());
    }

    @Test
    @DisplayName("a test may lower the minimum number of calls, but not past the window")
    void minimumCalls_isOverridable_butBounded() {
        var lowered = new PaymentBreakerProperties(20, null, null, null, 3, null, null, null, null);
        assertThat(ResilientPaymentGateway.breakerConfig(lowered).getMinimumNumberOfCalls())
                .isEqualTo(3);

        assertThatThrownBy(
                        () ->
                                new PaymentBreakerProperties(
                                        5, null, null, null, 6, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nonsensical limits are refused")
    void invalid_isRefused() {
        assertThatThrownBy(
                        () ->
                                new PaymentBreakerProperties(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Duration.ZERO,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PaymentBreakerProperties(
                                        null, null, null, null, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "the circuit state is published as resilience4j_circuitbreaker_state{name=paymentGateway}, live")
    void state_isPublishedUnderTheNameTheAlertUses() {
        var registry = CircuitBreakerRegistry.of(ResilientPaymentGateway.breakerConfig(defaults()));
        var breaker = registry.circuitBreaker("paymentGateway");
        var meters = new SimpleMeterRegistry();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);

        // observability.md §6 alerts on exactly this name and tag
        var open =
                meters.find("resilience4j.circuitbreaker.state")
                        .tag("name", "paymentGateway")
                        .tag("state", "open")
                        .gauge();
        assertThat(open).isNotNull();
        assertThat(open.value()).isZero();

        // recorded calls reach the meters; a micrometer API mismatch would fail here, not in prod
        breaker.onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        breaker.onError(
                1,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new com.unihub.payment.application.port.out.GatewayCallFailedException("x", null));
        assertThat(
                        meters.find("resilience4j.circuitbreaker.calls")
                                .tag("name", "paymentGateway")
                                .tag("kind", "successful")
                                .timer()
                                .count())
                .isEqualTo(1);

        breaker.transitionToOpenState();
        assertThat(open.value()).as("the alert fires on this reading").isEqualTo(1.0);
    }
}
