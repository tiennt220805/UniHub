package com.unihub.payment.application.port.in;

import com.unihub.payment.application.command.RefundPaymentCommand;

public interface RefundPaymentUseCase {

    /**
     * {@code REFUND_REQUIRED → REFUNDED} once the gateway reports the refund {@code succeeded}; at
     * most one refund exists on the gateway however often it is called (payment.md §9). A payment
     * that is already {@code REFUNDED} is left alone.
     *
     * @throws com.unihub.payment.application.port.out.GatewayCircuitOpenException
     * @throws com.unihub.payment.application.port.out.GatewayNotReachedException when the gateway
     *     was never asked: transient and never parked (checklist D25)
     * @throws com.unihub.payment.application.port.out.GatewayCallFailedException on any other
     *     gateway failure: transient
     */
    void refund(RefundPaymentCommand command);
}
