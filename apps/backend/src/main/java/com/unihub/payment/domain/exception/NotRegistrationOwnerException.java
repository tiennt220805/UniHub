package com.unihub.payment.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.RegistrationId;

/**
 * The caller is not the registration's owner (HTTP 403, nothing recorded). The message names no
 * owner: it must not tell a stranger whose registration this is.
 */
public class NotRegistrationOwnerException extends DomainException {
    public NotRegistrationOwnerException(RegistrationId id) {
        super("Registration " + id.value() + " does not belong to the caller");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.FORBIDDEN;
    }
}
