package com.unihub.shared.application.port.out;

import java.time.Instant;

/** Removes idempotency keys past their replay window (database-schema.md §8.1 Retention). */
public interface IdempotencyKeyCleanup {

    /**
     * Deletes the {@code COMPLETED} keys completed strictly before {@code cutoff}. An {@code
     * IN_PROGRESS} key is never deleted, whatever its age: a charge's open key is closed by
     * reconciliation or by the breaker compensation, not by time.
     *
     * @return how many keys were deleted
     */
    int deleteCompletedBefore(Instant cutoff);
}
