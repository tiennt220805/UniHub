package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;

/** A Workshop would violate one of its invariants (the same rules the schema CHECKs enforce). */
public class InvalidWorkshopException extends DomainException {

    public InvalidWorkshopException(String message) {
        super(message);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }
}
