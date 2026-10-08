package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.domain.model.WorkshopStatus;

/** The requested operation is not allowed in the workshop's current status (e.g. terminal). */
public class InvalidWorkshopStateException extends DomainException {

    public InvalidWorkshopStateException(WorkshopId id, WorkshopStatus current, String attempted) {
        super("Workshop " + id.value() + " is " + current + "; cannot " + attempted);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }
}
