package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;

/**
 * {@code HeadObject} found nothing at the issued cover key: the browser has not uploaded yet. HTTP
 * 404; nothing is persisted and the pending key stays, so the flow can be retried.
 */
public class CoverImageObjectMissingException extends DomainException {
    public CoverImageObjectMissingException(WorkshopId id) {
        super("The cover image of workshop " + id.value() + " has not been uploaded");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.NOT_FOUND;
    }
}
