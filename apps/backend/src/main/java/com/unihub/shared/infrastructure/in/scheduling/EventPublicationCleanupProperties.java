package com.unihub.shared.infrastructure.in.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.jobs.event-publication-cleanup.*}. {@code retention} is how long a {@code
 * PUBLISHED} outbox row stays, measured from {@code published_at}; {@code interval} is the pause
 * between sweeps.
 */
@ConfigurationProperties(prefix = "unihub.jobs.event-publication-cleanup")
public record EventPublicationCleanupProperties(Duration interval, Duration retention) {

    public EventPublicationCleanupProperties {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException(
                    "unihub.jobs.event-publication-cleanup.interval required");
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(
                    "unihub.jobs.event-publication-cleanup.retention required");
        }
    }
}
