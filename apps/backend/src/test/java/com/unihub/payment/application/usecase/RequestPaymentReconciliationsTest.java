package com.unihub.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.event.PaymentReconciliationRequested;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.id.PaymentId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The sweep's query half (payment.md §6): the 15-minute threshold and one request per payment. */
class RequestPaymentReconciliationsTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:30:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final RequestPaymentReconciliations sweep =
            new RequestPaymentReconciliations(
                    payments,
                    events,
                    new ReconciliationPolicy(Duration.ofMinutes(15), 200, Duration.ofHours(24)),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("asks for payments initiated before now minus 15 minutes, at most one batch")
    void asksForTheThreshold() {
        when(payments.findPendingInitiatedBefore(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        assertThat(sweep.requestReconciliations()).isZero();

        verify(payments).findPendingInitiatedBefore(NOW.minus(Duration.ofMinutes(15)), 200);
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("writes one reconciliation request per stale payment")
    void oneRequestPerPayment() {
        var first = PaymentId.newId();
        var second = PaymentId.newId();
        when(payments.findPendingInitiatedBefore(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(first, second));

        assertThat(sweep.requestReconciliations()).isEqualTo(2);

        var published = ArgumentCaptor.forClass(PaymentReconciliationRequested.class);
        verify(events, times(2)).publish(published.capture());
        assertThat(published.getAllValues())
                .extracting(PaymentReconciliationRequested::paymentId)
                .containsExactly(first.value(), second.value());
        assertThat(published.getValue().routingKey())
                .isEqualTo("unihub.payment.reconciliation.requested");
        assertThat(published.getValue().aggregateType()).isEqualTo("PAYMENT");
        assertThat(published.getValue().schemaVersion()).isEqualTo(1);
    }
}
