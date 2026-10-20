package com.unihub.registration.infrastructure.config;

import com.unihub.registration.application.usecase.RegistrationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RegistrationProperties.class)
class RegistrationConfig {

    @Bean
    RegistrationPolicy registrationPolicy(RegistrationProperties properties) {
        return new RegistrationPolicy(
                properties.seatHold(),
                properties.lockWait(),
                properties.lockLease(),
                properties.seatClaimAttempts());
    }
}
