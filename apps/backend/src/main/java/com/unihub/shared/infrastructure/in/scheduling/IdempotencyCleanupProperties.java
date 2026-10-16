package com.unihub.shared.infrastructure.in.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.jobs.idempotency-cleanup.*}. {@code retention} is how long a {@code COMPLETED} key
 * keeps replaying its Result, measured from {@code completed_at}; {@code interval} is the pause
 * between sweeps.
 */
@ConfigurationProperties(prefix = "unihub.jobs.idempotency-cleanup")
public record IdempotencyCleanupProperties(Duration interval, Duration retention) {

    public IdempotencyCleanupProperties {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("unihub.jobs.idempotency-cleanup.interval required");
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(
                    "unihub.jobs.idempotency-cleanup.retention required");
        }
    }
}
