package com.unihub.payment.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.port.out.RefundBacklog;
import com.unihub.shared.infrastructure.out.metrics.BackgroundGauges;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundStuckSignalsTest {
    private static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BackgroundGauges gauges = new BackgroundGauges(true);
    private final RefundBacklog backlog = mock(RefundBacklog.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock =
            new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneId.of("UTC");
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };

    @BeforeEach
    void attach() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void detach() {
        gauges.close();
        Metrics.removeRegistry(meters);
    }

    @Test
    @DisplayName("only a park from the refund queue ticks unihub_payment_refund_parked_total")
    void parkedCounter_isForTheRefundQueueOnly() {
        var signals = new RefundStuckSignals(backlog, clock, gauges, Metrics.globalRegistry);

        signals.parked("unihub.payment-refund-execution.queue");
        signals.parked("unihub.payment-refund-execution.queue");
        signals.parked("unihub.reconciliation.queue");
        signals.parked("unihub.seat-hold-expiry.queue");

        assertThat(meters.get(RefundStuckSignals.PARKED).counter().count()).isEqualTo(2.0);
        assertThat(RefundStuckSignals.PARKED).isEqualTo("unihub.payment.refund.parked");
    }

    @Test
    @DisplayName("the gauge is the age of the longest-waiting refund, and 0 when none is owed")
    void gauge_isTheOldestAge() {
        var signals = new RefundStuckSignals(backlog, clock, gauges, Metrics.globalRegistry);
        when(backlog.oldestOwedSince()).thenReturn(Optional.empty());
        assertThat(signals.oldestAgeSeconds()).isZero();

        now.set(T0.plus(Duration.ofMinutes(1)));
        when(backlog.oldestOwedSince()).thenReturn(Optional.of(T0.minusSeconds(90)));
        assertThat(signals.oldestAgeSeconds()).isEqualTo(150.0);
    }

    @Test
    @DisplayName("a failed read throws, so the background gauge reports unknown, not a stale age")
    void gauge_failedReadThrows() {
        var signals = new RefundStuckSignals(backlog, clock, gauges, Metrics.globalRegistry);
        when(backlog.oldestOwedSince()).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(signals::oldestAgeSeconds).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the registered gauge is fed in the background and never queries on a scrape")
    void gauge_isReadInTheBackground() {
        when(backlog.oldestOwedSince()).thenReturn(Optional.of(T0.minusSeconds(60)));
        // not attached to the global registry, which would hand back an earlier test's gauge
        var own = new SimpleMeterRegistry();
        var signals = new RefundStuckSignals(backlog, clock, gauges, own);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(own.get(RefundStuckSignals.OLDEST_AGE).gauge().value())
                                        .isEqualTo(60.0));
    }
}
