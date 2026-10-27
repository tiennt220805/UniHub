package com.unihub.payment.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.port.out.RefundBacklog;
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
        Metrics.removeRegistry(meters);
    }

    @Test
    @DisplayName("only a park from the refund queue ticks unihub_payment_refund_parked_total")
    void parkedCounter_isForTheRefundQueueOnly() {
        var signals = new RefundStuckSignals(backlog, clock);

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
        var signals = new RefundStuckSignals(backlog, clock);
        when(backlog.oldestOwedSince()).thenReturn(Optional.empty());
        assertThat(signals.oldestAgeSeconds()).isZero();

        now.set(T0.plus(Duration.ofMinutes(1)));
        when(backlog.oldestOwedSince()).thenReturn(Optional.of(T0.minusSeconds(90)));
        assertThat(signals.oldestAgeSeconds()).isEqualTo(150.0);
    }

    @Test
    @DisplayName("the gauge is debounced: one query per 10 seconds however often it is scraped")
    void gauge_isDebounced() {
        var signals = new RefundStuckSignals(backlog, clock);
        when(backlog.oldestOwedSince()).thenReturn(Optional.of(T0.minusSeconds(60)));

        for (int scrape = 0; scrape < 5; scrape++) {
            assertThat(signals.oldestAgeSeconds()).isEqualTo(60.0);
            now.set(now.get().plusSeconds(1));
        }
        verify(backlog, times(1)).oldestOwedSince();

        now.set(T0.plusSeconds(10));
        signals.oldestAgeSeconds();
        verify(backlog, times(2)).oldestOwedSince();
    }

    @Test
    @DisplayName("a failed read keeps the last value instead of hiding the signal")
    void gauge_keepsTheLastValueWhenTheReadFails() {
        var signals = new RefundStuckSignals(backlog, clock);
        when(backlog.oldestOwedSince()).thenReturn(Optional.of(T0.minusSeconds(60)));
        assertThat(signals.oldestAgeSeconds()).isEqualTo(60.0);

        now.set(T0.plusSeconds(30));
        when(backlog.oldestOwedSince()).thenThrow(new IllegalStateException("db down"));

        assertThat(signals.oldestAgeSeconds()).isEqualTo(60.0);
    }
}
