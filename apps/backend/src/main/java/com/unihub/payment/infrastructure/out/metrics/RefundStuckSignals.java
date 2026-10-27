package com.unihub.payment.infrastructure.out.metrics;

import com.unihub.payment.application.port.out.RefundBacklog;
import com.unihub.shared.messaging.ParkedMessageObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What tells an operator a refund is stuck (payment.md §9, observability.md §4, checklist D25).
 * Registered on the global registry like the other counters; Prometheus renders {@code
 * unihub_payment_refund_parked_total} and {@code
 * unihub_payment_refund_required_oldest_age_seconds}.
 *
 * <p>The counter ticks on <em>any</em> park from the refund queue (the gateway failed or cancelled
 * the refund, or it was still not final after the chain's three attempts), told by the shared error
 * handler. The gauge is the age of the longest-waiting refund, read from PostgreSQL and
 * <b>debounced</b>: at most one query per 10 seconds however often it is scraped.
 */
@Component
class RefundStuckSignals implements ParkedMessageObserver {
    static final String PARKED = "unihub.payment.refund.parked";
    static final String OLDEST_AGE = "unihub.payment.refund.required.oldest.age";
    static final String REFUND_QUEUE = "unihub.payment-refund-execution.queue";
    static final Duration DEBOUNCE = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(RefundStuckSignals.class);

    private final Counter parked = Metrics.counter(PARKED);
    private final RefundBacklog backlog;
    private final Clock clock;
    private Instant readAt;
    private double lastAgeSeconds;

    RefundStuckSignals(RefundBacklog backlog, Clock clock) {
        this.backlog = backlog;
        this.clock = clock;
        Gauge.builder(OLDEST_AGE, this, RefundStuckSignals::oldestAgeSeconds)
                .baseUnit("seconds")
                .register(Metrics.globalRegistry);
    }

    @Override
    public void parked(String queue) {
        if (REFUND_QUEUE.equals(queue)) {
            parked.increment();
        }
    }

    /** 0 when nothing is owed; a failed read keeps the last value rather than hiding the signal. */
    synchronized double oldestAgeSeconds() {
        var now = clock.instant();
        if (readAt != null && Duration.between(readAt, now).compareTo(DEBOUNCE) < 0) {
            return lastAgeSeconds;
        }
        try {
            Optional<Instant> since = backlog.oldestOwedSince();
            lastAgeSeconds =
                    since.map(s -> Math.max(0, Duration.between(s, now).toMillis() / 1000.0))
                            .orElse(0.0);
            readAt = now;
        } catch (RuntimeException e) {
            LOG.warn("Could not read the oldest refund owed: {}", e.toString());
        }
        return lastAgeSeconds;
    }
}
