package com.unihub.payment.application.port.in;

import com.unihub.payment.application.query.GetPaymentQuery;
import com.unihub.payment.application.result.PaymentResult;

public interface GetPaymentUseCase {

    /**
     * @throws com.unihub.payment.domain.exception.PaymentNotFoundException when there is none
     * @throws com.unihub.payment.domain.exception.NotRegistrationOwnerException when the payment is
     *     for someone else's registration
     */
    PaymentResult get(GetPaymentQuery query);
}
