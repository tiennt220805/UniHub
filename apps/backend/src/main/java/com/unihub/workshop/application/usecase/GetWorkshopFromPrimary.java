package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-after-own-write branch of {@link GetWorkshop}: the Master, no cache in either direction.
 * Writing to the cache here would be wrong too — it would hand every other caller a row this one
 * read, and the entry points of the cache are the student path and the invalidation after a write.
 * A DRAFT is visible: the caller manages workshops.
 */
@Service
class GetWorkshopFromPrimary {
    private final WorkshopRepository workshops;

    GetWorkshopFromPrimary(WorkshopRepository workshops) {
        this.workshops = workshops;
    }

    @Transactional(readOnly = true)
    @ReadFromPrimary
    public WorkshopResult get(GetWorkshopQuery query) {
        var id = WorkshopId.of(query.workshopId());
        return WorkshopResult.from(
                workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id)));
    }
}
