package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Permissions;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.port.in.GetWorkshopUseCase;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.WorkshopStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GetWorkshop implements GetWorkshopUseCase {
    private final WorkshopRepository workshops;

    GetWorkshop(WorkshopRepository workshops) {
        this.workshops = workshops;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkshopResult get(GetWorkshopQuery query) {
        var id = WorkshopId.of(query.workshopId());
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        // A DRAFT is "not found", never "forbidden", to a caller who may not manage workshops
        // (packages/api-contract getWorkshop): its existence must not be observable.
        if (workshop.status() == WorkshopStatus.DRAFT && !canSeeDrafts(query)) {
            throw new WorkshopNotFoundException(id);
        }
        return WorkshopResult.from(workshop);
    }

    private static boolean canSeeDrafts(GetWorkshopQuery query) {
        return query.permissions().contains(Permissions.WORKSHOP_CREATE)
                || query.permissions().contains(Permissions.WORKSHOP_EDIT);
    }
}
