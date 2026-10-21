package com.unihub.payment.application.port.in;

import com.unihub.payment.application.command.RequireRefundCommand;

public interface RequireRefundUseCase {

    /**
     * {@code SUCCEEDED → REFUND_REQUIRED}, once per payment however many times the message is
     * delivered. Changes the status only: calling the gateway to refund is Phase 3.
     */
    void requireRefund(RequireRefundCommand command);
}
