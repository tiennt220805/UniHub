package com.unihub.shared.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link Clock}: use cases read "now" from it once per operation
 * (backend-coding-standards.md §16); tests substitute {@code Clock.fixed(...)}.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
