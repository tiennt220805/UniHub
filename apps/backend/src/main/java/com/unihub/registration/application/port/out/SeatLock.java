package com.unihub.registration.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;

/**
 * The advisory per-workshop lock around a seat claim (registration.md §2). It decides who may
 * <em>attempt</em> the write; the version and CHECK in PostgreSQL decide whether it is accepted.
 */
public interface SeatLock {

    /**
     * Waits at most {@code waitTimeout} for the lock of this workshop; a different workshop never
     * contends. The caller acquires it BEFORE the transaction opens and closes the handle AFTER the
     * transaction has committed or rolled back (backend-coding-standards.md §19.2).
     *
     * <p>{@code leaseTime} is advisory: an adapter whose lock has no lease (the in-process one)
     * ignores it.
     *
     * @throws com.unihub.registration.domain.exception.SeatLockUnavailableException on timeout or,
     *     from Phase 3, when Redis is unreachable — never proceed without the lock
     */
    SeatLockHandle tryAcquire(WorkshopId workshopId, Duration waitTimeout, Duration leaseTime);
}
