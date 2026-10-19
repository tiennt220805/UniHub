package com.unihub.workshop.api;

import java.util.UUID;

/**
 * How another module tells Workshop that a seat count changed (workshop.md §2, "Invalidation
 * ownership"). The cache keys belong to Workshop; no other module touches them directly.
 */
public interface WorkshopCacheInvalidation {

    /**
     * {@code seats_taken} of this workshop changed. The cached seat count is dropped once the
     * caller's transaction commits (at once when there is none).
     */
    void seatsChanged(UUID workshopId);
}
