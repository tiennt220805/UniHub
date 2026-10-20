package com.unihub.payment.domain.exception;

import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.PaymentId;

/** A transition the payment state machine forbids (test-design-techniques.md §2). */
public class InvalidPaymentStateException extends DomainException {

    public InvalidPaymentStateException(PaymentId id, PaymentStatus from, String attempted) {
        super("Payment " + id.value() + " is " + from + " and cannot " + attempted);
    }

    public InvalidPaymentStateException(String message) {
        super(message);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }
}
