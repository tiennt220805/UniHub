package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.CreateWorkshopCommand;
import com.unihub.workshop.application.port.in.CreateWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.model.Workshop;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CreateWorkshop implements CreateWorkshopUseCase {
    private final WorkshopRepository workshops;

    CreateWorkshop(WorkshopRepository workshops) {
        this.workshops = workshops;
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
        return WorkshopResult.from(workshops.save(workshop));
    }
}
