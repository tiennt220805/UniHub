package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.CreateWorkshopCommand;
import com.unihub.workshop.application.port.in.CreateWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.model.Workshop;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CreateWorkshop implements CreateWorkshopUseCase {
    private final WorkshopRepository workshops;
    private final AuditTrail auditTrail;
    private final Clock clock;

    CreateWorkshop(WorkshopRepository workshops, AuditTrail auditTrail, Clock clock) {
        this.workshops = workshops;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorkshopResult create(CreateWorkshopCommand command) {
        var workshop =
                Workshop.draft(
                        WorkshopId.newId(),
                        command.title(),
                        command.description(),
                        command.speakerName(),
                        command.speakerBio(),
                        command.location(),
                        command.startTime(),
                        command.endTime(),
                        command.capacity(),
                        command.paid(),
                        command.price());
        var saved = workshops.save(workshop);
        auditTrail.record(
                AuditEntry.of(
                        command.actor(),
                        AuditActions.WORKSHOP_CREATED,
                        AuditTargets.WORKSHOP,
                        saved.id().value(),
                        clock.instant()));
        return WorkshopResult.from(saved);
    }
}
