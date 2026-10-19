package com.unihub.shared.infrastructure.in.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.jobs.outbox-relay.*} (messaging.md §5). {@code interval} is the pause between
 * cycles, {@code batchSize} the rows one cycle publishes at most.
 */
@ConfigurationProperties(prefix = "unihub.jobs.outbox-relay")
public record OutboxRelayProperties(Duration interval, int batchSize) {

    public OutboxRelayProperties {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("unihub.jobs.outbox-relay.interval required");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("unihub.jobs.outbox-relay.batch-size required");
        }
    }
}
