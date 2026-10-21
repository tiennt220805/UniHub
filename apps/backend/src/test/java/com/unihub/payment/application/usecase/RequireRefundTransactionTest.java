package com.unihub.payment.application.usecase;

import static com.unihub.payment.domain.model.PaymentBuilder.aPendingPayment;
import static com.unihub.payment.domain.model.PaymentBuilder.aResolvedPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.usecase.RequireRefundTransaction.Effect;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.domain.id.PaymentId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequireRefundTransactionTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RequireRefundTransaction transaction =
            new RequireRefundTransaction(payments, Clock.fixed(NOW, ZoneOffset.UTC));

    private RequireRefundCommand found(Payment payment) {
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        return new RequireRefundCommand(payment.id());
    }

    @Test
    @DisplayName("SUCCEEDED becomes REFUND_REQUIRED and is saved; nothing calls the gateway")
    void succeeded_becomesRefundRequired() {
        var payment = aResolvedPayment(PaymentStatus.SUCCEEDED).build();

        assertThat(transaction.attempt(found(payment))).isEqualTo(Effect.REFUND_REQUIRED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        verify(payments).save(payment);
    }

    @Test
    @DisplayName(
            "a payment already REFUND_REQUIRED or REFUNDED is a no-op however often it is told")
    void alreadyOnItsWay_isNoOp() {
        for (var status :
                new PaymentStatus[] {PaymentStatus.REFUND_REQUIRED, PaymentStatus.REFUNDED}) {
            var payment = aResolvedPayment(status).build();

            assertThat(transaction.attempt(found(payment))).isEqualTo(Effect.NO_OP);
            assertThat(payment.status()).isEqualTo(status);
        }
        verify(payments, never()).save(any());
    }

    @Test
    @DisplayName("a payment that took no money (PENDING, FAILED, CANCELLED) has nothing to refund")
    void noMoneyTaken_isNoOp() {
        for (var payment :
                new Payment[] {
                    aPendingPayment().build(),
                    aResolvedPayment(PaymentStatus.FAILED).build(),
                    aResolvedPayment(PaymentStatus.CANCELLED).build()
                }) {
            assertThat(transaction.attempt(found(payment))).isEqualTo(Effect.NO_OP);
        }
        verify(payments, never()).save(any());
    }

    @Test
    @DisplayName("an unknown payment is an error for the retry chain")
    void unknownPayment_throws() {
        var command = new RequireRefundCommand(PaymentId.newId());
        when(payments.findById(command.paymentId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transaction.attempt(command))
                .isInstanceOf(IllegalStateException.class);
    }
}
