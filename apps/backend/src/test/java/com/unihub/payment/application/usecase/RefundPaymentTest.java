package com.unihub.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.RefundPaymentCommand;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCircuitOpenException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.usecase.RefundPaymentSteps.Owed;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.RetryUntilAvailable;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the refund consumer does with each answer of the gateway (payment.md §9, checklist D25,
 * AC25.1-25.6): list first, create only when none exists, and only {@code succeeded} moves the
 * payment.
 */
class RefundPaymentTest {
    private static final PaymentId PAYMENT = PaymentId.newId();
    private static final Owed OWED = new Owed(PAYMENT, "pi_1");
    private static final RefundPaymentCommand COMMAND = new RefundPaymentCommand(PAYMENT);

    private final RefundPaymentSteps steps = mock(RefundPaymentSteps.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final RefundPayment refund = new RefundPayment(steps, gateway);

    private void owed() {
        when(steps.findOwed(PAYMENT)).thenReturn(Optional.of(OWED));
    }

    @Test
    @DisplayName("nothing owed (already REFUNDED): the gateway is not asked at all")
    void nothingOwed_isNoOp() {
        when(steps.findOwed(PAYMENT)).thenReturn(Optional.empty());

        refund.refund(COMMAND);

        verifyNoInteractions(gateway);
        verify(steps, never()).markRefunded(any());
    }

    @Test
    @DisplayName("no refund on the gateway yet: one is created, and succeeded moves the payment")
    void noRefundYet_createsOne_thenRefunded() {
        owed();
        when(gateway.findRefund("pi_1")).thenReturn(Optional.empty());
        when(gateway.refund(PAYMENT, "pi_1")).thenReturn(GatewayRefundState.SUCCEEDED);

        refund.refund(COMMAND);

        verify(gateway).refund(PAYMENT, "pi_1");
        verify(steps).markRefunded(PAYMENT);
    }

    @Test
    @DisplayName("a refund the gateway already holds is used: no second one is created (AC25.2)")
    void existingRefund_isUsed_neverCreatedAgain() {
        owed();
        when(gateway.findRefund("pi_1")).thenReturn(Optional.of(GatewayRefundState.SUCCEEDED));

        refund.refund(COMMAND);

        verify(gateway, never()).refund(any(), any());
        verify(steps).markRefunded(PAYMENT);
    }

    @Test
    @DisplayName("pending: the payment stays REFUND_REQUIRED and the chain asks again (AC25.3)")
    void pending_isTransient() {
        owed();
        when(gateway.findRefund("pi_1")).thenReturn(Optional.of(GatewayRefundState.PENDING));

        assertThatThrownBy(() -> refund.refund(COMMAND))
                .isInstanceOf(RefundNotFinalException.class)
                .isNotInstanceOf(NonRetryableMessageException.class)
                .isNotInstanceOf(RetryUntilAvailable.class);

        verify(gateway, never()).refund(any(), any());
        verify(steps, never()).markRefunded(any());
    }

    @Test
    @DisplayName("a refund just created that is still pending is also asked about again")
    void createdButPending_isTransient() {
        owed();
        when(gateway.findRefund("pi_1")).thenReturn(Optional.empty());
        when(gateway.refund(PAYMENT, "pi_1")).thenReturn(GatewayRefundState.PENDING);

        assertThatThrownBy(() -> refund.refund(COMMAND))
                .isInstanceOf(RefundNotFinalException.class);

        verify(steps, never()).markRefunded(any());
    }

    @Test
    @DisplayName(
            "failed or cancelled: permanent, straight to the parking lot, payment unchanged (AC25.4)")
    void failed_isPermanent() {
        owed();
        when(gateway.findRefund("pi_1")).thenReturn(Optional.of(GatewayRefundState.FAILED));

        assertThatThrownBy(() -> refund.refund(COMMAND))
                .isInstanceOf(NonRetryableMessageException.class);

        verify(steps, never()).markRefunded(any());
    }

    @Test
    @DisplayName(
            "a gateway that was never reached keeps the message in the chain, unparked (AC25.5)")
    void gatewayNotReached_isRetryUntilAvailable() {
        owed();
        when(gateway.findRefund("pi_1"))
                .thenThrow(new GatewayCircuitOpenException("the payment circuit is open", null));

        assertThatThrownBy(() -> refund.refund(COMMAND))
                .isInstanceOf(GatewayCircuitOpenException.class)
                .isInstanceOf(RetryUntilAvailable.class);

        doReturn(Optional.empty()).when(gateway).findRefund("pi_1");
        doThrow(new GatewayNotReachedException("connection refused", null))
                .when(gateway)
                .refund(PAYMENT, "pi_1");
        assertThatThrownBy(() -> refund.refund(COMMAND))
                .isInstanceOf(GatewayNotReachedException.class)
                .isInstanceOf(RetryUntilAvailable.class);
        verify(steps, never()).markRefunded(any());
    }

    @Test
    @DisplayName("any other gateway failure is transient under the normal three-attempt rule")
    void otherGatewayFailure_isOrdinary() {
        owed();
        when(gateway.findRefund("pi_1")).thenThrow(new GatewayCallFailedException("5xx", null));

        assertThatThrownBy(() -> refund.refund(COMMAND))
                .isInstanceOf(GatewayCallFailedException.class)
                .isNotInstanceOf(RetryUntilAvailable.class);
    }

    @Test
    @DisplayName("a lost version race recording the refund is retried; three in a row surface")
    void lostRace_isRetried() {
        owed();
        when(gateway.findRefund("pi_1")).thenReturn(Optional.of(GatewayRefundState.SUCCEEDED));
        when(steps.markRefunded(PAYMENT))
                .thenThrow(new OptimisticConflictException("PAYMENT", PAYMENT.value()))
                .thenReturn(RefundPaymentSteps.Effect.REFUNDED);

        refund.refund(COMMAND);

        verify(steps, times(2)).markRefunded(PAYMENT);

        when(steps.markRefunded(PAYMENT))
                .thenThrow(new OptimisticConflictException("PAYMENT", PAYMENT.value()));
        assertThatThrownBy(() -> refund.refund(COMMAND))
                .isInstanceOf(OptimisticConflictException.class);
        verify(steps, times(5)).markRefunded(PAYMENT);
    }
}
