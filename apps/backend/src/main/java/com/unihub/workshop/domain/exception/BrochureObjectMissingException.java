package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;

/**
 * {@code HeadObject} found nothing at the issued key (workshop.md §7): the browser has not uploaded
 * yet, or never will. HTTP 404; nothing is persisted and the pending key stays, so the Organizer
 * can retry the flow.
 */
public class BrochureObjectMissingException extends DomainException {

    public BrochureObjectMissingException(WorkshopId id) {
        super("The brochure for workshop " + id.value() + " has not been uploaded");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.NOT_FOUND;
    }
}
