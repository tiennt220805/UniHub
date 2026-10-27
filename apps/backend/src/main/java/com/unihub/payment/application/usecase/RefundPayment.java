package com.unihub.payment.application.usecase;

import com.unihub.payment.application.command.RefundPaymentCommand;
import com.unihub.payment.application.port.in.RefundPaymentUseCase;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.usecase.RefundPaymentSteps.Owed;
import com.unihub.shared.domain.OptimisticConflictException;
import org.springframework.stereotype.Service;

/**
 * The refund consumer's use case (payment.md §9, checklist D25) — NOT {@code @Transactional}, so
 * the gateway is asked outside any transaction and a lost version race is retried in a new one,
 * whose re-read finds the payment already refunded.
 *
 * <p>The refunds the gateway already holds for the PaymentIntent are listed before one is created:
 * the gateway replays the saved answer of a repeated idempotency key (and forgets the key after a
 * day), so creating again could neither show a refund progressing nor prevent a second one for
 * long. Only {@code succeeded} moves the payment; a refund that is not final yet is asked about
 * again by the retry chain; a failed or cancelled one is parked for an operator.
 */
@Service
class RefundPayment implements RefundPaymentUseCase {
    private static final int ATTEMPTS = 3;

    private final RefundPaymentSteps steps;
    private final PaymentGateway gateway;

    RefundPayment(RefundPaymentSteps steps, PaymentGateway gateway) {
        this.steps = steps;
        this.gateway = gateway;
    }

    @Override
    public void refund(RefundPaymentCommand command) {
        var owed = steps.findOwed(command.paymentId());
        if (owed.isEmpty()) {
            return;
        }
        var state = stateOnTheGateway(owed.get());
        switch (state) {
            case SUCCEEDED -> markRefunded(command);
            case PENDING -> throw new RefundNotFinalException(owed.get().gatewayTransactionId());
            case FAILED ->
                    throw new RefundRefusedByGatewayException(owed.get().gatewayTransactionId());
        }
    }

    private GatewayRefundState stateOnTheGateway(Owed owed) {
        return gateway.findRefund(owed.gatewayTransactionId())
                .orElseGet(() -> gateway.refund(owed.id(), owed.gatewayTransactionId()));
    }

    private void markRefunded(RefundPaymentCommand command) {
        for (int attempt = 1; ; attempt++) {
            try {
                steps.markRefunded(command.paymentId());
                return;
            } catch (OptimisticConflictException lostRace) {
                if (attempt >= ATTEMPTS) {
                    throw lostRace;
                }
            }
        }
    }
}
