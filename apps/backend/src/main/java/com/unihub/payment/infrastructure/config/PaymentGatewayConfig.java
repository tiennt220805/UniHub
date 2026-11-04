package com.unihub.payment.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.port.out.GatewayAdmission;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentWebhookVerifier;
import com.unihub.payment.infrastructure.out.client.BreakerGatewayAdmission;
import com.unihub.payment.infrastructure.out.client.PaymentBreakerProperties;
import com.unihub.payment.infrastructure.out.client.ResilientPaymentGateway;
import com.unihub.payment.infrastructure.out.client.StripeGatewayFactory;
import com.unihub.payment.infrastructure.out.client.StripeProperties;
import com.unihub.payment.infrastructure.out.client.StripeWebhookProperties;
import com.unihub.payment.infrastructure.out.client.StripeWebhookVerifier;
import com.unihub.payment.infrastructure.out.client.UnconfiguredPaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    StripeProperties.class,
    StripeWebhookProperties.class,
    PaymentBreakerProperties.class
})
class PaymentGatewayConfig {

    /** The one circuit breaker of the module, shared by the charge and reconciliation. */
    @Bean
    CircuitBreaker paymentGatewayBreaker(PaymentBreakerProperties settings) {
        var registry = CircuitBreakerRegistry.of(ResilientPaymentGateway.breakerConfig(settings));
        // The global registry, as the other counters of this system:
        // resilience4j_circuitbreaker_state
        // (observability.md §4, §6) reaches Prometheus when the metrics stack lands.
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
                .bindTo(io.micrometer.core.instrument.Metrics.globalRegistry);
        return registry.circuitBreaker("paymentGateway");
    }

    /**
     * Every gateway call goes through the circuit. With no Stripe secret key the application still
     * boots: the gateway behind the circuit is then one that fails every call, and a charge answers
     * 503 with nothing charged.
     */
    @Bean
    PaymentGateway paymentGateway(
            StripeProperties stripe,
            PaymentBreakerProperties settings,
            CircuitBreaker paymentGatewayBreaker,
            ObservationRegistry observations) {
        PaymentGateway delegate =
                stripe.secretKey() == null || stripe.secretKey().isBlank()
                        ? new UnconfiguredPaymentGateway()
                        : StripeGatewayFactory.create(stripe, observations);
        return new ResilientPaymentGateway(delegate, paymentGatewayBreaker, settings);
    }

    @Bean
    PaymentWebhookVerifier paymentWebhookVerifier(
            StripeWebhookProperties properties, ObjectMapper json, Clock clock) {
        return new StripeWebhookVerifier(properties, json, clock);
    }

    @Bean
    GatewayAdmission gatewayAdmission(CircuitBreaker paymentGatewayBreaker) {
        return new BreakerGatewayAdmission(paymentGatewayBreaker);
    }
}
