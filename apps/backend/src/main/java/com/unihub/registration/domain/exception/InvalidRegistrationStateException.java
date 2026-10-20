package com.unihub.registration.domain.exception;

import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.RegistrationId;

/** A transition the registration state machine forbids (test-design-techniques.md §2). */
public class InvalidRegistrationStateException extends DomainException {

    public InvalidRegistrationStateException(
            RegistrationId id, RegistrationStatus from, String attempted) {
        super("Registration " + id.value() + " is " + from + " and cannot " + attempted);
    }

    public InvalidRegistrationStateException(String message) {
        super(message);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }
}
