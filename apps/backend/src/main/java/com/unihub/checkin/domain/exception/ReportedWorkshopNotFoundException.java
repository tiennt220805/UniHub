package com.unihub.checkin.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.id.WorkshopId;

/** The workshop a report or an attendance list was asked for does not exist. */
public class ReportedWorkshopNotFoundException extends DomainException {

    public ReportedWorkshopNotFoundException(WorkshopId id) {
        super("Workshop " + id.value() + " not found");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.NOT_FOUND;
    }
}
