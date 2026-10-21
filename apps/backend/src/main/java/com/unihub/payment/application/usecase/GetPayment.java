package com.unihub.payment.application.usecase;

import com.unihub.payment.application.port.in.GetPaymentUseCase;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.query.GetPaymentQuery;
import com.unihub.payment.application.result.PaymentResult;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.payment.domain.exception.PaymentNotFoundException;
import com.unihub.registration.api.PayableRegistrations;
import com.unihub.shared.application.routing.ReadFromPrimary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one payment for the owner of its registration — the endpoint a client polls while a charge
 * is {@code PENDING}. From the Master, not the Replica: a lagging Replica would show a stale {@code
 * PENDING} after the payment resolved (INV-03). Ownership is the rule here, not a permission code,
 * and it is Registration that knows the owner.
 */
@Service
class GetPayment implements GetPaymentUseCase {
    private final PaymentRepository payments;
    private final PayableRegistrations registrations;

    GetPayment(PaymentRepository payments, PayableRegistrations registrations) {
        this.payments = payments;
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public PaymentResult get(GetPaymentQuery query) {
        var payment =
                payments.findById(query.paymentId())
                        .orElseThrow(() -> new PaymentNotFoundException(query.paymentId()));
        var owner =
                registrations
                        .find(payment.registrationId())
                        .map(registration -> registration.userId())
                        .orElse(null);
        if (!query.caller().equals(owner)) {
            throw new NotRegistrationOwnerException(payment.registrationId());
        }
        return PaymentResult.of(payment);
    }
}
