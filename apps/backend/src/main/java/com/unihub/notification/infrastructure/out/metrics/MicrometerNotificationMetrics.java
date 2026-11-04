package com.unihub.notification.infrastructure.out.metrics;

import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationMetrics;
import com.unihub.notification.application.port.out.NotificationRepository;
import com.unihub.shared.infrastructure.out.metrics.BackgroundGauges;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code unihub_notification_delivery_total{channel,result}} and the age of the oldest {@code
 * PENDING} row (Phase 4 checklist 2.6). Registered on the global registry, like the other modules'
 * metrics; the gauge is read from PostgreSQL in the background, never on a scrape.
 */
@Component
class MicrometerNotificationMetrics implements NotificationMetrics {
    static final String DELIVERY = "unihub.notification.delivery";
    static final String OLDEST_PENDING_AGE = "unihub.notification.pending.oldest.age.seconds";

    private final MeterRegistry registry;

    @Autowired
    MicrometerNotificationMetrics(
            NotificationRepository notifications, Clock clock, BackgroundGauges gauges) {
        this(Metrics.globalRegistry, notifications, clock, gauges);
    }

    MicrometerNotificationMetrics(
            MeterRegistry registry,
            NotificationRepository notifications,
            Clock clock,
            BackgroundGauges gauges) {
        this.registry = registry;
        gauges.register(
                registry,
                OLDEST_PENDING_AGE,
                "Age of the oldest notification still PENDING; 0 when none waits",
                "seconds",
                () ->
                        notifications
                                .oldestPendingCreatedAt()
                                .map(created -> Duration.between(created, clock.instant()))
                                .map(age -> (double) age.toMillis() / 1000.0)
                                .orElse(0.0));
    }

    @Override
    public void delivered(String channel, NotificationDeliveryResult result) {
        registry.counter(DELIVERY, "channel", channel, "result", result.name()).increment();
    }

    @Override
    public void channelThrew(String channel) {
        registry.counter(DELIVERY, "channel", channel, "result", "THREW").increment();
    }
}
