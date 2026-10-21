package com.unihub.payment.infrastructure.config;

import com.unihub.payment.application.port.out.GatewayAdmission;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.infrastructure.out.client.AlwaysAdmittingGatewayAdmission;
import com.unihub.payment.infrastructure.out.client.StripeGatewayFactory;
import com.unihub.payment.infrastructure.out.client.StripeProperties;
import com.unihub.payment.infrastructure.out.client.UnconfiguredPaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StripeProperties.class)
class PaymentGatewayConfig {

    /** Only when a secret key is set: without one the application boots with no gateway. */
    @Bean
    @ConditionalOnExpression("!'${unihub.payment.stripe.secret-key:}'.isBlank()")
    PaymentGateway paymentGateway(StripeProperties properties) {
        return StripeGatewayFactory.create(properties);
    }

    /** Without a key the application still boots; a charge then answers 503 (nothing charged). */
    @Bean
    @ConditionalOnExpression("'${unihub.payment.stripe.secret-key:}'.isBlank()")
    PaymentGateway unconfiguredPaymentGateway() {
        return new UnconfiguredPaymentGateway();
    }

    @Bean
    GatewayAdmission gatewayAdmission() {
        return new AlwaysAdmittingGatewayAdmission();
    }
}
