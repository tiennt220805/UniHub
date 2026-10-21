package com.unihub.payment.application.command;

import com.unihub.shared.domain.id.PaymentId;
import java.util.Objects;

/** Registration refused to confirm this payment's registration: the payment is to be refunded. */
public record RequireRefundCommand(PaymentId paymentId) {

    public RequireRefundCommand {
        Objects.requireNonNull(paymentId);
    }
}
