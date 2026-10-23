package com.unihub.payment.application.command;

import com.unihub.shared.domain.id.PaymentId;
import java.util.Objects;

/** Reconciliation asked to look at this payment against the gateway. */
public record ReconcilePaymentCommand(PaymentId paymentId) {

    public ReconcilePaymentCommand {
        Objects.requireNonNull(paymentId);
    }
}
