package com.unihub.notification.domain.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The module's own settings as plain values, built from configuration in {@code
 * infrastructure.config} (the application layer holds no Spring configuration types).
 *
 * @param claimLease how long a row's send claim holds (notification.md §3, Phase 4 checklist D33):
 *     longer than the worst case of one send, well under the retry chain's total delay
 * @param channelsByType which channels a type is sent through; the publisher never decides this
 */
public record NotificationPolicy(
        Duration claimLease, Map<NotificationType, List<String>> channelsByType) {

    public NotificationPolicy {
        if (claimLease == null || claimLease.isNegative() || claimLease.isZero()) {
            throw new IllegalArgumentException("claim lease must be positive");
        }
        var copy = new java.util.EnumMap<NotificationType, List<String>>(NotificationType.class);
        for (var type : NotificationType.values()) {
            var channels = channelsByType.get(type);
            if (channels == null || channels.isEmpty()) {
                throw new IllegalArgumentException("no channel configured for " + type);
            }
            copy.put(type, List.copyOf(channels));
        }
        channelsByType = java.util.Collections.unmodifiableMap(copy);
    }

    public List<String> channelsOf(NotificationType type) {
        return channelsByType.get(type);
    }
}
