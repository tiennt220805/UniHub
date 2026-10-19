package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import java.util.Optional;

/**
 * Cache-aside storage for the public catalog (workshop.md §2). Never the source of truth: emptying
 * it at any moment leaves the system correct, only slower. Therefore no method throws and an
 * unreachable cache reads as a miss; a seat-reservation decision never goes through here.
 */
public interface WorkshopCache {

    Optional<WorkshopDetailSnapshot> findDetail(WorkshopId id);

    void putDetail(WorkshopDetailSnapshot snapshot);

    Optional<WorkshopSeatsSnapshot> findSeats(WorkshopId id);

    void putSeats(WorkshopSeatsSnapshot snapshot);

    /** {@code criteria} must already be normalised: the cache key is derived from it as given. */
    Optional<WorkshopCatalogPage> findList(SearchWorkshopsCriteria criteria);

    void putList(SearchWorkshopsCriteria criteria, WorkshopCatalogPage page);

    /**
     * Drops the detail and seats entries of this workshop after the surrounding transaction commits
     * (at once if there is none): dropping them earlier would let a concurrent reader re-cache the
     * old row before the new one is visible. Lists are not dropped; they expire (workshop.md §2).
     */
    void invalidate(WorkshopId id);

    /** Same, for the seats entry only: a seat count changed, the slow-changing fields did not. */
    void invalidateSeats(WorkshopId id);
}
