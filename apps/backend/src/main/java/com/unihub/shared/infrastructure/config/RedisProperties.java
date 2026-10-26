package com.unihub.shared.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code unihub.redis.*} (distributed-coordination.md §1). {@code address} (e.g. {@code
 * redis://host:6379}) and {@code password} come from the environment only ({@code
 * UNIHUB_REDIS_ADDRESS}, {@code UNIHUB_REDIS_PASSWORD}) — never from committed config (M-13). The
 * address is required: without it the application does not start (checklist D21).
 *
 * <p>The timeouts are deliberately short and the client does not retry: every Redis user here
 * either degrades to the database (the catalog cache) or refuses cleanly (locks), so waiting
 * seconds on an unreachable Redis helps none of them.
 */
@ConfigurationProperties(prefix = "unihub.redis")
public record RedisProperties(
        String address,
        String password,
        @DefaultValue("PT0.5S") Duration connectTimeout,
        @DefaultValue("PT0.5S") Duration commandTimeout) {

    public RedisProperties {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException(
                    "unihub.redis.address is required (set UNIHUB_REDIS_ADDRESS, e.g."
                            + " redis://localhost:6379): the seat lock and the job lock have no"
                            + " in-process fallback");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("unihub.redis.connect-timeout must be positive");
        }
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("unihub.redis.command-timeout must be positive");
        }
    }
}
