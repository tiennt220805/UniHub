package com.unihub.payment.application.usecase;

import static com.unihub.payment.application.usecase.AuditAssertions.assertRecorded;
import static com.unihub.payment.application.usecase.AuditAssertions.assertSystemActor;
import static com.unihub.payment.application.usecase.AuditAssertions.onlyEntry;
import static com.unihub.payment.domain.model.PaymentBuilder.aPendingPayment;
import static com.unihub.payment.domain.model.PaymentBuilder.aResolvedPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.api.event.PaymentSucceededIntegrationEvent;
import com.unihub.payment.application.event.PaymentRefundRequested;
import com.unihub.payment.application.port.out.GatewayPaymentEvent;
import com.unihub.payment.application.port.out.GatewayPaymentEvent.Outcome;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.application.usecase.PaymentWebhookTransaction.Effect;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditTrail;
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
    private final AuditTrail audit = mock(AuditTrail.class);
    private final PaymentWebhookTransaction transaction =
            new PaymentWebhookTransaction(
                    payments, idempotency, events, audit, Clock.fixed(NOW, ZoneOffset.UTC));

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
            "a success for a FAILED or CANCELLED payment is a late success: REFUND_REQUIRED + a refund job, no confirmation")
    void closed_lateSuccess(PaymentStatus closed) {
        var payment = found(aResolvedPayment(closed).build());

        assertThat(transaction.apply(event(Outcome.SUCCEEDED))).isEqualTo(Effect.LATE_SUCCESS);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        verify(payments).save(payment);
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(events).publish(message.capture());
        assertThat(message.getValue())
                .as("the refund job, never charge.succeeded: nothing may confirm the registration")
                .isInstanceOf(PaymentRefundRequested.class);
        assertThat(message.getValue().aggregateId()).isEqualTo(payment.id().value());
        verifyNoMoreInteractions(events);
        verifyNoInteractions(idempotency);
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

    // ---------- audit (checklist D6): the webhook is a process, never a user ----------

    @Test
    @DisplayName("audit: a payment the webhook settles is recorded once, under the webhook process")
    void audit_pendingSucceeded() {
        var payment = found(aPendingPayment().build());

        transaction.apply(event(Outcome.SUCCEEDED));

        var entry = onlyEntry(audit);
        assertRecorded(entry, AuditActions.PAYMENT_SUCCEEDED, payment, NOW);
        assertSystemActor(entry, "payment-webhook");
    }

    @Test
    @DisplayName("audit: a failed and a cancelled payment are recorded as such")
    void audit_pendingFailedAndCancelled() {
        var failed = found(aPendingPayment().build());
        transaction.apply(event(Outcome.FAILED));
        assertRecorded(onlyEntry(audit), AuditActions.PAYMENT_FAILED, failed, NOW);

        org.mockito.Mockito.reset(audit);
        var cancelled = found(aPendingPayment().build());
        transaction.apply(event(Outcome.CANCELLED));
        assertRecorded(onlyEntry(audit), AuditActions.PAYMENT_CANCELLED, cancelled, NOW);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"FAILED", "CANCELLED"})
    @DisplayName(
            "audit: a late success for a closed payment is recorded as PAYMENT_REFUND_REQUIRED")
    void audit_lateSuccess(PaymentStatus closed) {
        var payment = found(aResolvedPayment(closed).build());

        transaction.apply(event(Outcome.SUCCEEDED));

        var entry = onlyEntry(audit);
        assertRecorded(entry, AuditActions.PAYMENT_REFUND_REQUIRED, payment, NOW);
        assertSystemActor(entry, "payment-webhook");
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"SUCCEEDED", "REFUND_REQUIRED", "REFUNDED"})
    @DisplayName("audit: a redelivery to a payment that already moved records nothing (AC2.2)")
    void audit_redelivery_recordsNothing(PaymentStatus settled) {
        found(aResolvedPayment(settled).build());

        transaction.apply(event(Outcome.SUCCEEDED));

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("audit: a transaction no payment has records nothing")
    void audit_unknownTransaction_recordsNothing() {
        when(payments.findByGatewayTransactionId(GATEWAY_ID)).thenReturn(Optional.empty());

        transaction.apply(event(Outcome.SUCCEEDED));

        verifyNoInteractions(audit);
    }
}
