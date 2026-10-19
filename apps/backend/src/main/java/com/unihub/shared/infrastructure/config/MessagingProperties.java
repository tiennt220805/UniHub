package com.unihub.shared.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code unihub.messaging.*}. {@code confirmTimeout} is how long a publish waits for the broker's
 * confirm before the message counts as not delivered (messaging.md §5: 5s).
 */
@ConfigurationProperties(prefix = "unihub.messaging")
public record MessagingProperties(@DefaultValue("PT5S") Duration confirmTimeout) {

    public MessagingProperties {
        if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("unihub.messaging.confirm-timeout must be positive");
        }
    }
}
