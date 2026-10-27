package com.unihub.shared.infrastructure.config;

import com.unihub.shared.infrastructure.out.messaging.Topology;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code unihub.messaging.*}. {@code confirmTimeout} is how long a publish waits for the broker's
 * confirm before the message counts as not delivered (messaging.md §5: 5s). {@code retryDelays} are
 * the three stages of every retry chain (messaging.md §4: 5s, 30s, 5m); only a test changes them.
 */
@ConfigurationProperties(prefix = "unihub.messaging")
public record MessagingProperties(
        @DefaultValue("PT5S") Duration confirmTimeout,
        @DefaultValue({"PT5S", "PT30S", "PT5M"}) List<Duration> retryDelays) {

    public MessagingProperties {
        if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("unihub.messaging.confirm-timeout must be positive");
        }
        if (retryDelays == null
                || retryDelays.size() != Topology.RETRY_STAGES.size()
                || retryDelays.stream().anyMatch(d -> d.isNegative() || d.isZero())) {
            throw new IllegalArgumentException(
                    "unihub.messaging.retry-delays needs "
                            + Topology.RETRY_STAGES.size()
                            + " positive durations");
        }
        retryDelays = List.copyOf(retryDelays);
    }
}
