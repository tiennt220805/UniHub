package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopDetailSnapshot;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.port.out.WorkshopSeatsSnapshot;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.WorkshopStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The browsing branch of {@link GetWorkshop}: cache-aside, a miss reads the Replica. Staleness
 * within the replication lag is accepted here (architecture.md §4.1, workshop.md §2).
 */
@Service
class GetWorkshopFromCache {
    private final WorkshopRepository workshops;
    private final WorkshopCache cache;

    GetWorkshopFromCache(WorkshopRepository workshops, WorkshopCache cache) {
        this.workshops = workshops;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public WorkshopResult get(GetWorkshopQuery query) {
        var id = WorkshopId.of(query.workshopId());
        var result = load(id);
        // A DRAFT is "not found", never "forbidden", to a caller who may not manage workshops
        // (packages/api-contract getWorkshop): its existence must not be observable. Checked after
        // the cache, which holds drafts too: the entry is shared by every caller, the gate is not.
        if (WorkshopStatus.DRAFT.name().equals(result.status())) {
            throw new WorkshopNotFoundException(id);
        }
        return result;
    }

    /**
     * Cache-aside over two independently expiring entries (workshop.md §2). Both present: no
     * database read at all. Either missing: one Replica read refills the missing entry. A workshop
     * that does not exist is never cached.
     */
    private WorkshopResult load(WorkshopId id) {
        var detail = cache.findDetail(id);
        var seats = cache.findSeats(id);
        if (detail.isPresent() && seats.isPresent()) {
            return WorkshopResult.from(detail.get(), seats.get());
        }
        var workshop = workshops.findById(id).orElseThrow(() -> new WorkshopNotFoundException(id));
        if (detail.isEmpty()) {
            cache.putDetail(WorkshopDetailSnapshot.from(workshop));
        }
        if (seats.isEmpty()) {
            cache.putSeats(WorkshopSeatsSnapshot.from(workshop));
        }
        return WorkshopResult.from(workshop);
    }
}
