package com.unihub.payment.infrastructure.out.metrics;

import com.unihub.payment.application.port.out.RefundBacklog;
import com.unihub.shared.infrastructure.out.metrics.BackgroundGauges;
import com.unihub.shared.messaging.ParkedMessageObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What tells an operator a refund is stuck (payment.md §9, observability.md §4, checklist D25).
 * Registered on the global registry like the other counters; Prometheus renders {@code
 * unihub_payment_refund_parked_total} and {@code
 * unihub_payment_refund_required_oldest_age_seconds}.
 *
 * <p>The counter ticks on <em>any</em> park from the refund queue (the gateway failed or cancelled
 * the refund, or it was still not final after the chain's three attempts). The gauge is the age of
 * the longest-waiting refund, read from PostgreSQL by a {@link BackgroundGauge} every 10 seconds
 * and never on the scrape thread.
 */
@Component
class RefundStuckSignals implements ParkedMessageObserver {
    static final String PARKED = "unihub.payment.refund.parked";
    static final String OLDEST_AGE = "unihub.payment.refund.required.oldest.age";
    static final String REFUND_QUEUE = "unihub.payment-refund-execution.queue";

    private final Counter parked = Metrics.counter(PARKED);
    private final RefundBacklog backlog;
    private final Clock clock;

    @Autowired
    RefundStuckSignals(RefundBacklog backlog, Clock clock, BackgroundGauges gauges) {
        this(backlog, clock, gauges, Metrics.globalRegistry);
    }

    RefundStuckSignals(
            RefundBacklog backlog, Clock clock, BackgroundGauges gauges, MeterRegistry registry) {
        this.backlog = backlog;
        this.clock = clock;
        gauges.register(
                registry,
                OLDEST_AGE,
                "Age of the refund that has waited longest; 0 when none is owed",
                "seconds",
                this::oldestAgeSeconds);
    }

    @Override
    public void parked(String queue) {
        if (REFUND_QUEUE.equals(queue)) {
            parked.increment();
        }
    }

    /** 0 when nothing is owed; a failed read throws, and the gauge then reports unknown. */
    double oldestAgeSeconds() {
        var now = clock.instant();
        return backlog.oldestOwedSince()
                .map(since -> Math.max(0, Duration.between(since, now).toMillis() / 1000.0))
                .orElse(0.0);
    }
}
