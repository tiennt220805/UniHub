package com.unihub.payment.infrastructure.config;

import com.unihub.payment.application.usecase.ReconciliationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
class ReconciliationConfig {

    @Bean
    ReconciliationPolicy reconciliationPolicy(ReconciliationProperties properties) {
        return new ReconciliationPolicy(
                properties.staleAfter(), properties.batchSize(), properties.stuckAfter());
    }
}
