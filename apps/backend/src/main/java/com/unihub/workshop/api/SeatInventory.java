package com.unihub.workshop.api;

import com.unihub.shared.domain.id.WorkshopId;

/**
 * How Registration takes and returns seats (backend-coding-standards.md §19.5). Both methods must
 * run inside the caller's transaction: the {@code workshops} update and the caller's own write
 * commit together or not at all (database-schema.md §3.1 same-transaction invariant), and an {@code
 * IllegalTransactionStateException} is the answer to a call made without one.
 */
public interface SeatInventory {

    /**
     * Takes one seat if the workshop is open and not full. A lost version race is an {@link
     * com.unihub.shared.domain.OptimisticConflictException}, never a {@code SoldOut}: the caller
     * retries it in a new transaction.
     */
    SeatClaimOutcome claim(WorkshopId workshopId);

    /** Returns one seat. Throws {@code OptimisticConflictException} on a lost version race. */
    void release(WorkshopId workshopId);
}
