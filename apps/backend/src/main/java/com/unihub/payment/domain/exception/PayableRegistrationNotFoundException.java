package com.unihub.payment.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.RegistrationId;

/** No registration with this id: nothing to pay for (HTTP 404, not recorded under the key). */
public class PayableRegistrationNotFoundException extends DomainException {
    public PayableRegistrationNotFoundException(RegistrationId id) {
        super("Registration " + id.value() + " was not found");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.NOT_FOUND;
    }
}
