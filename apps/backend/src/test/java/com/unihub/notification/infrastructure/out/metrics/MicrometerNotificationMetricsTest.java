package com.unihub.notification.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.notification.application.port.out.NewNotification;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationRepository;
import com.unihub.notification.application.port.out.PendingNotification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counters of Phase 4 checklist 2.6, on a {@code SimpleMeterRegistry} (no exporter before Phase
 * 6).
 */
class MicrometerNotificationMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private final AtomicReference<Optional<Instant>> oldest =
            new AtomicReference<>(Optional.empty());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerNotificationMetrics metrics =
            new MicrometerNotificationMetrics(
                    registry, repository(), Clock.fixed(NOW, ZoneOffset.UTC));

    private NotificationRepository repository() {
        return new NotificationRepository() {
            public int insertIfAbsent(List<NewNotification> rows) {
                return 0;
            }

            public List<PendingNotification> findPending(UUID id) {
                return List.of();
            }

            public int countPending(UUID id) {
                return 0;
            }

            public Optional<Instant> claim(UUID id, Duration lease) {
                return Optional.empty();
            }

            public void release(UUID id, Instant token) {}

            public void markSent(UUID id) {}

            public void markFailed(UUID id) {}

            public int failPending(UUID id) {
                return 0;
            }

            public Optional<Instant> oldestPendingCreatedAt() {
                return oldest.get();
            }
        };
    }

    @Test
    @DisplayName("each delivery result is counted per channel and result")
    void deliveries_areCounted() {
        metrics.delivered("EMAIL", NotificationDeliveryResult.SENT);
        metrics.delivered("EMAIL", NotificationDeliveryResult.SENT);
        metrics.delivered("EMAIL", NotificationDeliveryResult.UNAVAILABLE);
        metrics.channelThrew("IN_APP");

        assertThat(count("EMAIL", "SENT")).isEqualTo(2.0);
        assertThat(count("EMAIL", "UNAVAILABLE")).isEqualTo(1.0);
        assertThat(count("IN_APP", "THREW")).isEqualTo(1.0);
        assertThat(count("EMAIL", "REJECTED")).isZero();
    }

    @Test
    @DisplayName(
            "the oldest PENDING row's age is 0 with none waiting, and grows with the oldest one")
    void oldestPendingAge() {
        var gauge = registry.get(MicrometerNotificationMetrics.OLDEST_PENDING_AGE).gauge();
        assertThat(gauge.value()).isZero();

        oldest.set(Optional.of(NOW.minusSeconds(90)));

        assertThat(gauge.value()).isEqualTo(90.0);
    }

    private double count(String channel, String result) {
        var counter =
                registry.find(MicrometerNotificationMetrics.DELIVERY)
                        .tags("channel", channel, "result", result)
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
