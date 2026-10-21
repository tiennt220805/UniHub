package com.unihub.registration.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.RegistrationId;

/** No registration with this id (HTTP 404). */
public class RegistrationNotFoundException extends DomainException {
    public RegistrationNotFoundException(RegistrationId id) {
        super("Registration " + id.value() + " was not found");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.NOT_FOUND;
    }
}
