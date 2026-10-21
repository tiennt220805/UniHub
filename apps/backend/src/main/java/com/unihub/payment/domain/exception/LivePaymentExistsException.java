package com.unihub.payment.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.RegistrationId;

/**
 * Another live payment ({@code PENDING} or {@code SUCCEEDED}) already exists for the registration:
 * the partial unique index {@code uq_payments_live_per_registration} refused this one (D5). A
 * domain failure, never an infrastructure error. The use case turns it into a recorded Result; if
 * it ever escapes, it still answers 409 with the same problem {@code type}.
 */
public class LivePaymentExistsException extends DomainException {
    public LivePaymentExistsException(RegistrationId registrationId, Throwable cause) {
        super("Registration " + registrationId.value() + " already has a live payment", cause);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }

    @Override
    public String problemType() {
        return "urn:unihub:problem:payment-in-progress";
    }
}
