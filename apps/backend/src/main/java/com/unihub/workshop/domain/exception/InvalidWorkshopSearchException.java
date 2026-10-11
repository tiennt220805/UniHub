package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;

/** A catalog search whose filters contradict each other (e.g. {@code dateFrom} after dateTo). */
public class InvalidWorkshopSearchException extends DomainException {

    public InvalidWorkshopSearchException(String message) {
        super(message);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.INVALID_REQUEST;
    }
}
