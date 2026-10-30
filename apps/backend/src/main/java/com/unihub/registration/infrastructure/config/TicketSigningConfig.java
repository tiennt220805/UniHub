package com.unihub.registration.infrastructure.config;

import com.unihub.registration.infrastructure.out.crypto.TicketSigningKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ticket signing key is required configuration (Phase 5 checklist D49), like {@code
 * unihub.redis.address} (D21) and {@code unihub.notification.smtp.host} (D36): a blank or invalid
 * value stops the start with a message that says what to set. The bean is a singleton, so the check
 * runs at start, not at the first ticket.
 */
@Configuration
@EnableConfigurationProperties(TicketSigningProperties.class)
class TicketSigningConfig {

    @Bean
    TicketSigningKey ticketSigningKey(TicketSigningProperties properties) {
        return TicketSigningKey.parse(properties.signingKey());
    }
}
