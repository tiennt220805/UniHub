package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;

/**
 * A cover upload request the bean validation of the web layer should already have refused (an
 * unsupported type, a size outside the limit): the use case checks again because it is the one that
 * owns the rule (Phase 8, D122). HTTP 400.
 */
public class InvalidCoverImageRequestException extends DomainException {
    public InvalidCoverImageRequestException(String message) {
        super(message);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.INVALID_REQUEST;
    }
}
