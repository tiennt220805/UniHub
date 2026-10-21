package com.unihub.registration.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.RegistrationId;

/**
 * The caller is not the registration's owner (HTTP 403). Ownership, not a permission code, is the
 * rule for reading a registration (the e-ticket is in it). The message names no owner.
 */
public class RegistrationNotOwnedException extends DomainException {
    public RegistrationNotOwnedException(RegistrationId id) {
        super("Registration " + id.value() + " does not belong to the caller");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.FORBIDDEN;
    }
}
