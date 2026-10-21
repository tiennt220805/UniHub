package com.unihub.payment.application.query;

import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.UserId;
import java.util.Objects;

/** One payment, read by the caller; allowed only to the owner of its registration. */
public record GetPaymentQuery(UserId caller, PaymentId paymentId) {

    public GetPaymentQuery {
        Objects.requireNonNull(caller);
        Objects.requireNonNull(paymentId);
    }
}
