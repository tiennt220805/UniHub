package com.unihub.payment.application.usecase;

import static com.unihub.payment.application.usecase.AuditAssertions.assertRecorded;
import static com.unihub.payment.application.usecase.AuditAssertions.assertSystemActor;
import static com.unihub.payment.application.usecase.AuditAssertions.onlyEntry;
import static com.unihub.payment.domain.model.PaymentBuilder.aPendingPayment;
import static com.unihub.payment.domain.model.PaymentBuilder.aResolvedPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.usecase.RefundPaymentSteps.Effect;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two short transactions around the refund's gateway calls (payment.md §9, checklist D25). */
class RefundPaymentStepsTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final RefundPaymentSteps steps =
            new RefundPaymentSteps(payments, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    private Payment found(Payment payment) {
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        return payment;
    }

    private static Payment owed() {
        return aResolvedPayment(PaymentStatus.REFUND_REQUIRED)
                .withGatewayTransactionId("pi_1")
                .build();
    }

    @Test
    @DisplayName("a REFUND_REQUIRED payment is owed: the gateway id to ask about is handed out")
    void refundRequired_isOwed() {
        var payment = found(owed());

        var result = steps.findOwed(payment.id());

        assertThat(result).contains(new RefundPaymentSteps.Owed(payment.id(), "pi_1"));
    }

    @Test
    @DisplayName("REFUNDED is a redelivery: nothing is owed, the gateway is not asked")
    void refunded_isNothingOwed() {
        var payment =
                found(
                        aResolvedPayment(PaymentStatus.REFUNDED)
                                .withGatewayTransactionId("pi_1")
                                .build());

        assertThat(steps.findOwed(payment.id())).isEmpty();
    }

    @Test
    @DisplayName(
            "a payment in any other status owes nothing (only this job's producer moves a row)")
    void otherStatuses_oweNothing() {
        for (var payment :
                new Payment[] {
                    aPendingPayment().build(),
                    aResolvedPayment(PaymentStatus.SUCCEEDED).build(),
                    aResolvedPayment(PaymentStatus.FAILED).build(),
                    aResolvedPayment(PaymentStatus.CANCELLED).build()
                }) {
            assertThat(steps.findOwed(found(payment).id())).isEmpty();
        }
    }

    @Test
    @DisplayName("an unknown payment, or one owed with no gateway id, is an error for the chain")
    void impossibleStates_throw() {
        var unknown = PaymentId.newId();
        when(payments.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> steps.findOwed(unknown)).isInstanceOf(IllegalStateException.class);

        var noGatewayId = found(aResolvedPayment(PaymentStatus.REFUND_REQUIRED).build());
        assertThatThrownBy(() -> steps.findOwed(noGatewayId.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
            "REFUND_REQUIRED becomes REFUNDED by a saved transition, audited under the process")
    void markRefunded_movesAndAudits() {
        var payment = found(owed());

        assertThat(steps.markRefunded(payment.id())).isEqualTo(Effect.REFUNDED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        verify(payments).save(payment);
        var entry = onlyEntry(audit);
        assertRecorded(entry, AuditActions.PAYMENT_REFUNDED, payment, NOW);
        assertSystemActor(entry, "payment-refund");
    }

    @Test
    @DisplayName("a payment that is no longer REFUND_REQUIRED is left as it is: no save, no audit")
    void markRefunded_isNoOpElsewhere() {
        for (var status : new PaymentStatus[] {PaymentStatus.REFUNDED, PaymentStatus.SUCCEEDED}) {
            var payment = found(aResolvedPayment(status).build());

            assertThat(steps.markRefunded(payment.id())).isEqualTo(Effect.NO_OP);
        }
        verify(payments, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a lost version race on the save is a conflict for the caller to retry, unaudited")
    void markRefunded_lostRace() {
        var payment = found(owed());
        doThrow(new OptimisticConflictException("PAYMENT", payment.id().value()))
                .when(payments)
                .save(any());

        assertThatThrownBy(() -> steps.markRefunded(payment.id()))
                .isInstanceOf(OptimisticConflictException.class);
    }
}
