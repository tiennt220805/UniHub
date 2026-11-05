package com.unihub.shared.infrastructure.config;

import com.unihub.shared.application.port.out.RateLimiter;
import com.unihub.shared.infrastructure.out.ratelimit.RedissonRateLimiter;
import com.unihub.shared.infrastructure.security.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Redisson {@link RateLimiter}. Registered whether or not {@code unihub.rate-limit.enabled}:
 * the switch only decides whether the filters charge anything, and the {@code
 * unihub_rate_limit_bypassed_total} series must exist at 0 in every context (observability.md §4).
 */
@Configuration
class RateLimiterConfig {

    @Bean
    RateLimiter rateLimiter(
            RedissonClient redis,
            Clock clock,
            RateLimitProperties properties,
            MeterRegistry meters) {
        return new RedissonRateLimiter(redis, clock, properties.failureCooldown(), meters);
    }
}
