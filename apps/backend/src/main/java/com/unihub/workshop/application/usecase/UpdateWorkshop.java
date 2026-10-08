package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.UpdateWorkshopCommand;
import com.unihub.workshop.application.port.in.UpdateWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UpdateWorkshop implements UpdateWorkshopUseCase {
    private final WorkshopRepository workshops;
    private final Clock clock;

    UpdateWorkshop(WorkshopRepository workshops, Clock clock) {
        this.workshops = workshops;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorkshopResult update(UpdateWorkshopCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));

        // Both steps check the status first, so a terminal workshop rejects even an empty update.
        // A failure in either rolls the whole transaction back: nothing is saved half-applied.
        workshop.revise(
                command.title().applyTo(workshop.title()),
                command.description().applyTo(workshop.description()),
                command.speakerName().applyTo(workshop.speakerName()),
                command.speakerBio().applyTo(workshop.speakerBio()),
                command.location().applyTo(workshop.location()),
                command.capacity().applyTo(workshop.capacity()),
                command.price().applyTo(workshop.price()));
        workshop.reschedule(
                command.startTime().applyTo(workshop.startTime()),
                command.endTime().applyTo(workshop.endTime()),
                clock.instant());

        return WorkshopResult.from(workshops.save(workshop));
    }
}
