package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.WorkshopCacheInvalidation;
import com.unihub.workshop.application.port.out.WorkshopCache;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Not {@code @Transactional}: it joins the caller's transaction, and the cache waits for its
 * commit.
 */
@Service
class InvalidateWorkshopSeats implements WorkshopCacheInvalidation {

    private final WorkshopCache cache;

    InvalidateWorkshopSeats(WorkshopCache cache) {
        this.cache = cache;
    }

    @Override
    public void seatsChanged(UUID workshopId) {
        cache.invalidateSeats(WorkshopId.of(workshopId));
    }
}
