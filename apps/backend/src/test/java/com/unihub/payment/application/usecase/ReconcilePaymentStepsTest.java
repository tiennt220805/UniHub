package com.unihub.payment.application.usecase;

import static com.unihub.payment.domain.model.PaymentBuilder.aPendingPayment;
import static com.unihub.payment.domain.model.PaymentBuilder.aResolvedPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.api.event.PaymentSucceededIntegrationEvent;
import com.unihub.payment.application.port.out.GatewayPaymentEvent.Outcome;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.usecase.PaymentWebhookTransaction.Effect;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.PaymentId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The transactions around reconciliation's gateway calls: the read of what is still pending, and
 * the resolution through the mapping the webhook shares (payment.md §6, G2).
 */
class ReconcilePaymentStepsTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:30:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ReconcilePaymentSteps steps =
            new ReconcilePaymentSteps(
                    payments, idempotency, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private Payment found(Payment payment) {
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(payments.save(payment)).thenReturn(payment);
        return payment;
    }

    @Test
    @DisplayName("findPending reports a PENDING payment with its gateway id and start time")
    void findPending_pending() {
        var payment = found(aPendingPayment().withGatewayTransactionId("pi_1").build());

        var pending = steps.findPending(payment.id());

        assertThat(pending).isPresent();
        assertThat(pending.get().gatewayTransactionId()).isEqualTo("pi_1");
        assertThat(pending.get().initiatedAt()).isEqualTo(payment.initiatedAt());
    }

    @Test
    @DisplayName("findPending reports nothing for any payment that is no longer PENDING")
    void findPending_resolved() {
        for (var status : PaymentStatus.values()) {
            if (status == PaymentStatus.PENDING) {
                continue;
            }
            var payment = found(aResolvedPayment(status).build());

            assertThat(steps.findPending(payment.id())).as(status.name()).isEmpty();
        }
    }

    @Test
    @DisplayName("a payment that does not exist is a bug, not a no-op")
    void unknownPayment_isRefused() {
        var id = PaymentId.newId();
        when(payments.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> steps.findPending(id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> steps.resolve(id, Outcome.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
            "SUCCEEDED resolves the payment, writes charge.succeeded and completes its open key")
    void resolve_succeeded() {
        var payment = found(aPendingPayment().withGatewayTransactionId("pi_1").build());

        assertThat(steps.resolve(payment.id(), Outcome.SUCCEEDED)).isEqualTo(Effect.RESOLVED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.resolvedAt()).isEqualTo(NOW);
        var message = ArgumentCaptor.forClass(PaymentSucceededIntegrationEvent.class);
        verify(events).publish(message.capture());
        assertThat(message.getValue().paymentId()).isEqualTo(payment.id().value());
        verify(idempotency)
                .completeOpenFor(
                        org.mockito.ArgumentMatchers.eq(IdempotencyScope.PAYMENT_CHARGE),
                        org.mockito.ArgumentMatchers.eq(payment.id().value()),
                        any());
    }

    @Test
    @DisplayName("FAILED resolves with no event and completes the key: the outcome is known")
    void resolve_failed() {
        var payment = found(aPendingPayment().withGatewayTransactionId("pi_1").build());

        steps.resolve(payment.id(), Outcome.FAILED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(events);
        verify(idempotency).completeOpenFor(any(), any(), any());
        verify(idempotency, never()).abandonOpenFor(any(), any());
    }

    @Test
    @DisplayName("CANCELLED resolves with no event and deletes the key: no money moved")
    void resolve_cancelled() {
        var payment = found(aPendingPayment().build());

        steps.resolve(payment.id(), Outcome.CANCELLED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        verifyNoInteractions(events);
        verify(idempotency).abandonOpenFor(IdempotencyScope.PAYMENT_CHARGE, payment.id().value());
        verify(idempotency, never()).completeOpenFor(any(), any(), any());
    }

    @Test
    @DisplayName("a payment the webhook resolved first is left as it is, key and outbox untouched")
    void resolve_alreadyResolvedByTheWebhook_isNoOp() {
        var payment = found(aResolvedPayment(PaymentStatus.SUCCEEDED).build());

        assertThat(steps.resolve(payment.id(), Outcome.CANCELLED)).isEqualTo(Effect.NO_OP);
        assertThat(steps.resolve(payment.id(), Outcome.SUCCEEDED)).isEqualTo(Effect.NO_OP);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(payments, never()).save(any());
        verifyNoInteractions(events, idempotency);
    }

    @Test
    @DisplayName("a success for a payment already CANCELLED is a refund, never a confirmation")
    void resolve_lateSuccess() {
        var payment = found(aResolvedPayment(PaymentStatus.CANCELLED).build());

        assertThat(steps.resolve(payment.id(), Outcome.SUCCEEDED)).isEqualTo(Effect.LATE_SUCCESS);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        verifyNoInteractions(events);
    }
}
