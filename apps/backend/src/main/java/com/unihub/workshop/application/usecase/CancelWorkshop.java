package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.CancelWorkshopCommand;
import com.unihub.workshop.application.port.in.CancelWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CancelWorkshop implements CancelWorkshopUseCase {
    private final WorkshopRepository workshops;
    private final Clock clock;

    CancelWorkshop(WorkshopRepository workshops, Clock clock) {
        this.workshops = workshops;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorkshopResult cancel(CancelWorkshopCommand command) {
        var id = WorkshopId.of(command.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        workshop.cancel(clock.instant());
        return WorkshopResult.from(workshops.save(workshop));
    }
}
