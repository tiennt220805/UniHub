package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;

public class WorkshopNotFoundException extends DomainException {

    public WorkshopNotFoundException(WorkshopId id) {
        super("Workshop " + id.value() + " not found");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.NOT_FOUND;
    }
}
