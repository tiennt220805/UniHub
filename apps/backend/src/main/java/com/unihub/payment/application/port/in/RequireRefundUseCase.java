package com.unihub.payment.application.port.in;

import com.unihub.payment.application.command.RequireRefundCommand;

public interface RequireRefundUseCase {

    /**
     * {@code SUCCEEDED → REFUND_REQUIRED}, once per payment however many times the message is
     * delivered, with the refund job written in the same transaction. Never calls the gateway.
     */
    void requireRefund(RequireRefundCommand command);
}
