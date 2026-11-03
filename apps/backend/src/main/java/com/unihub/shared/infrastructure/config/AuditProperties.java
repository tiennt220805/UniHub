package com.unihub.shared.infrastructure.config;

import com.unihub.shared.infrastructure.out.messaging.Topology;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code unihub.audit.*} (Phase 6 checklist D68, D70). The defaults are the production values; only
 * a test changes them, to reach a full executor or a full queue in a few messages.
 *
 * <p>{@code executorQueueCapacity}: entries waiting for the publisher thread (a full queue drops
 * and counts). {@code confirmTimeout}, {@code connectionTimeout}: how long the publisher thread
 * waits for the broker's confirm, and to connect or to open a channel. {@code drainTimeout}: how
 * long shutdown lets queued entries go out. {@code queueMaxLength}: the length bound of {@code
 * unihub.audit.queue}.
 */
@ConfigurationProperties(prefix = "unihub.audit")
public record AuditProperties(
        @DefaultValue("10000") int executorQueueCapacity,
        @DefaultValue("PT5S") Duration confirmTimeout,
        @DefaultValue("PT5S") Duration connectionTimeout,
        @DefaultValue("PT5S") Duration drainTimeout,
        @DefaultValue("" + Topology.AUDIT_QUEUE_MAX_LENGTH) int queueMaxLength) {

    public AuditProperties {
        if (executorQueueCapacity < 1 || queueMaxLength < 1) {
            throw new IllegalArgumentException(
                    "unihub.audit.executor-queue-capacity and queue-max-length must be positive");
        }
        for (var duration : new Duration[] {confirmTimeout, connectionTimeout, drainTimeout}) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("unihub.audit timeouts must be positive");
            }
        }
    }
}
