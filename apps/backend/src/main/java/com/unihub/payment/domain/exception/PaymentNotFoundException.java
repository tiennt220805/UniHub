package com.unihub.payment.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.PaymentId;

/** No payment with this id (HTTP 404). */
public class PaymentNotFoundException extends DomainException {
    public PaymentNotFoundException(PaymentId id) {
        super("Payment " + id.value() + " was not found");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.NOT_FOUND;
    }
}
