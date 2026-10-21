package com.unihub.payment.application.usecase;

import static com.unihub.payment.domain.model.PaymentBuilder.aPendingPayment;
import static com.unihub.payment.domain.model.PaymentBuilder.aResolvedPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.api.event.PaymentSucceededIntegrationEvent;
import com.unihub.payment.application.port.out.GatewayPaymentEvent;
import com.unihub.payment.application.port.out.GatewayPaymentEvent.Outcome;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.application.usecase.PaymentWebhookTransaction.Effect;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.event.OutboxMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/**
 * The single mapping of "what the gateway says" onto a payment (payment.md §6, §7, §9; checklist
 * D2, D5): one row per current status, for each outcome the gateway can report.
 */
class PaymentWebhookTransactionTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");
    private static final String GATEWAY_ID = "pi_123";

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final PaymentWebhookTransaction transaction =
            new PaymentWebhookTransaction(
                    payments, idempotency, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private Payment found(Payment payment) {
        when(payments.findByGatewayTransactionId(GATEWAY_ID)).thenReturn(Optional.of(payment));
        when(payments.save(payment)).thenReturn(payment);
        return payment;
    }

    private static GatewayPaymentEvent event(Outcome outcome) {
        return new GatewayPaymentEvent(GATEWAY_ID, outcome);
    }

    @Test
    @DisplayName("a transaction no payment has is discarded, touching nothing")
    void unknownTransaction() {
        when(payments.findByGatewayTransactionId(GATEWAY_ID)).thenReturn(Optional.empty());

        assertThat(transaction.apply(event(Outcome.SUCCEEDED)))
                .isEqualTo(Effect.UNKNOWN_TRANSACTION);

        verifyNoInteractions(events, idempotency);
        verify(payments, never()).save(any());
    }

    @Test
    @DisplayName(
            "a PENDING payment reported successful is SUCCEEDED, with charge.succeeded and its key completed")
    void pending_succeeded() {
        var payment = found(aPendingPayment().build());

        assertThat(transaction.apply(event(Outcome.SUCCEEDED))).isEqualTo(Effect.RESOLVED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(events).publish(message.capture());
        assertThat(message.getValue())
                .isEqualTo(
                        new PaymentSucceededIntegrationEvent(
                                payment.id().value(), payment.registrationId().value(), NOW));
        var snapshot = ArgumentCaptor.forClass(Object.class);
        verify(idempotency)
                .completeOpenFor(
                        any(IdempotencyScope.class), any(java.util.UUID.class), snapshot.capture());
        assertThat(((ChargePaymentResult.Processed) snapshot.getValue()).status())
                .isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("a PENDING payment reported failed is FAILED: no event, the key completed")
    void pending_failed() {
        var payment = found(aPendingPayment().build());

        assertThat(transaction.apply(event(Outcome.FAILED))).isEqualTo(Effect.RESOLVED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(events);
        verify(idempotency).completeOpenFor(any(), any(), any());
        verify(idempotency, never()).abandonOpenFor(any(), any());
    }

    @Test
    @DisplayName(
            "a PENDING payment reported canceled is CANCELLED: no money moved, so its open key is dropped")
    void pending_canceled() {
        var payment = found(aPendingPayment().build());

        assertThat(transaction.apply(event(Outcome.CANCELLED))).isEqualTo(Effect.RESOLVED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        verify(idempotency).abandonOpenFor(IdempotencyScope.PAYMENT_CHARGE, payment.id().value());
        verify(idempotency, never()).completeOpenFor(any(), any(), any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"FAILED", "CANCELLED"})
    @DisplayName(
            "a success for a FAILED or CANCELLED payment is a late success: REFUND_REQUIRED, no event")
    void closed_lateSuccess(PaymentStatus closed) {
        var payment = found(aResolvedPayment(closed).build());

        assertThat(transaction.apply(event(Outcome.SUCCEEDED))).isEqualTo(Effect.LATE_SUCCESS);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        verify(payments).save(payment);
        verifyNoInteractions(events, idempotency);
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    @DisplayName(
            "an already SUCCEEDED, REFUND_REQUIRED or REFUNDED payment is left alone, whatever is reported")
    void resolved_isNoOp(Outcome outcome) {
        for (var status :
                new PaymentStatus[] {
                    PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_REQUIRED, PaymentStatus.REFUNDED
                }) {
            var payment = found(aResolvedPayment(status).build());

            assertThat(transaction.apply(event(outcome)))
                    .as(status + "/" + outcome)
                    .isEqualTo(Effect.NO_OP);
            assertThat(payment.status()).isEqualTo(status);
        }
        verifyNoInteractions(events, idempotency);
        verify(payments, never()).save(any());
    }

    @Test
    @DisplayName("a FAILED or CANCELLED payment is not touched by a failure or cancellation report")
    void closed_otherOutcomes_areNoOp() {
        for (var status : new PaymentStatus[] {PaymentStatus.FAILED, PaymentStatus.CANCELLED}) {
            for (var outcome : new Outcome[] {Outcome.FAILED, Outcome.CANCELLED}) {
                var payment = found(aResolvedPayment(status).build());

                assertThat(transaction.apply(event(outcome))).isEqualTo(Effect.NO_OP);
                assertThat(payment.status()).isEqualTo(status);
            }
        }
        verifyNoInteractions(events, idempotency);
    }
}
