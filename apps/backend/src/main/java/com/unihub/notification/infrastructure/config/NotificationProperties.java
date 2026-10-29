package com.unihub.notification.infrastructure.config;

import com.unihub.notification.domain.model.NotificationType;
import java.time.Duration;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code unihub.notification.*} (notification.md §2-§4; Phase 4 checklist D33, D36, D37, D39).
 * Credentials (SMTP username and password) come from the environment only, never committed
 * (INV-18). The SMTP host is required: a blank one stops the start with a clear message, so a
 * misconfigured environment cannot silently turn every email into {@code UNAVAILABLE}; a host that
 * is set but unreachable at runtime does not stop the start.
 *
 * @param claimLease the send lease: longer than the worst case of one send (connect timeout plus
 *     about eight round trips at the read/write timeout, roughly 85s), well under the retry chain's
 *     total delay (about 5.6 minutes)
 * @param displayZone the zone times are shown in (payloads are UTC instants)
 * @param channels which channels each type is sent through, keyed by the type in kebab or snake
 *     case ({@code workshop-cancelled}); a type without an entry uses {@code EMAIL} and {@code
 *     IN_APP}. This module's decision, never the publisher's.
 */
@ConfigurationProperties(prefix = "unihub.notification")
public record NotificationProperties(
        @DefaultValue Smtp smtp,
        @DefaultValue("PT120S") Duration claimLease,
        @DefaultValue("Asia/Ho_Chi_Minh") ZoneId displayZone,
        @DefaultValue Listener listener,
        Map<String, List<String>> channels) {

    static final List<String> DEFAULT_CHANNELS = List.of("EMAIL", "IN_APP");

    /** SMTP through Jakarta Mail. All three timeouts are set because each defaults to forever. */
    public record Smtp(
            String host,
            @DefaultValue("25") int port,
            String username,
            String password,
            @DefaultValue("noreply@unihub.local") String from,
            @DefaultValue("false") boolean starttls,
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT10S") Duration readTimeout,
            @DefaultValue("PT10S") Duration writeTimeout) {

        public Smtp {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException(
                        "unihub.notification.smtp.host is required (set"
                                + " UNIHUB_NOTIFICATION_SMTP_HOST, e.g. localhost for Mailpit): a"
                                + " blank host would turn every email into UNAVAILABLE");
            }
            for (var timeout : List.of(connectTimeout, readTimeout, writeTimeout)) {
                if (timeout.isNegative() || timeout.isZero()) {
                    throw new IllegalArgumentException(
                            "unihub.notification.smtp timeouts must be positive");
                }
            }
        }
    }

    /**
     * The notification queue's own listener container (D39): two threads, one message each, so a
     * large fan-out does not hold every later message behind it.
     */
    public record Listener(@DefaultValue("2") int concurrency, @DefaultValue("1") int prefetch) {

        public Listener {
            if (concurrency < 1 || prefetch < 1) {
                throw new IllegalArgumentException(
                        "unihub.notification.listener needs concurrency and prefetch >= 1");
            }
        }
    }

    public NotificationProperties {
        if (claimLease.isNegative() || claimLease.isZero()) {
            throw new IllegalArgumentException("unihub.notification.claim-lease must be positive");
        }
    }

    /** The configured channels per type; a type with no entry uses {@link #DEFAULT_CHANNELS}. */
    Map<NotificationType, List<String>> channelsByType() {
        var byType = new EnumMap<NotificationType, List<String>>(NotificationType.class);
        for (var type : NotificationType.values()) {
            byType.put(type, DEFAULT_CHANNELS);
        }
        if (channels != null) {
            channels.forEach(
                    (key, value) -> {
                        var type = NotificationType.valueOf(key.toUpperCase().replace('-', '_'));
                        byType.put(type, List.copyOf(value));
                    });
        }
        return byType;
    }
}
