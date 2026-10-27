package com.unihub.payment.application.command;

import com.unihub.shared.domain.id.PaymentId;
import java.util.Objects;

/**
 * This payment is owed back: refund it through the gateway if it is still {@code REFUND_REQUIRED}.
 */
public record RefundPaymentCommand(PaymentId paymentId) {

    public RefundPaymentCommand {
        Objects.requireNonNull(paymentId);
    }
}
