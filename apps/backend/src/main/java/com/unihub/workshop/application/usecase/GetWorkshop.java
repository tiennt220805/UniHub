package com.unihub.workshop.application.usecase;

import com.unihub.shared.security.Permissions;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.port.in.GetWorkshopUseCase;
import com.unihub.workshop.application.result.WorkshopResult;
import org.springframework.stereotype.Service;

/**
 * Entry point of {@code getWorkshop}; deliberately not {@code @Transactional}. Which node answers
 * depends on who asks, and {@code @ReadFromPrimary} only takes effect on the method that starts the
 * transaction, called through the proxy — so the choice is made here and each branch is its own
 * bean (backend-coding-standards.md §25, the same two-bean shape as {@code ReserveSeat}).
 *
 * <ul>
 *   <li>A caller who manages workshops reads the Master and never the cache: this is the "organizer
 *       viewing a workshop right after creating or editing it" read that must reflect the write
 *       (architecture.md §4.1, §20). The cache is shared by every caller and can hold a row a
 *       student's miss refilled from a lagging Replica, so it cannot serve this caller either.
 *   <li>Everyone else gets the cache-aside read, which tolerates replication lag (workshop.md §2).
 * </ul>
 */
@Service
class GetWorkshop implements GetWorkshopUseCase {
    private final GetWorkshopFromPrimary fromPrimary;
    private final GetWorkshopFromCache fromCache;

    GetWorkshop(GetWorkshopFromPrimary fromPrimary, GetWorkshopFromCache fromCache) {
        this.fromPrimary = fromPrimary;
        this.fromCache = fromCache;
    }

    @Override
    public WorkshopResult get(GetWorkshopQuery query) {
        return managesWorkshops(query) ? fromPrimary.get(query) : fromCache.get(query);
    }

    /** The permissions that may also see a DRAFT, and that read their own writes. */
    static boolean managesWorkshops(GetWorkshopQuery query) {
        return query.permissions().contains(Permissions.WORKSHOP_CREATE)
                || query.permissions().contains(Permissions.WORKSHOP_EDIT);
    }
}
