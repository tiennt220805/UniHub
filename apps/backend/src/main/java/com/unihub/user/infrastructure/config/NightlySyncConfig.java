package com.unihub.user.infrastructure.config;

import com.unihub.user.application.usecase.NightlySyncPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NightlySyncProperties.class)
class NightlySyncConfig {

    @Bean
    NightlySyncPolicy nightlySyncPolicy(NightlySyncProperties properties) {
        return new NightlySyncPolicy(properties.zone(), properties.batchSize());
    }
}
