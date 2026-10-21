package com.unihub.payment.infrastructure.config;

import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.infrastructure.out.client.StripeGatewayFactory;
import com.unihub.payment.infrastructure.out.client.StripeProperties;
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
}
