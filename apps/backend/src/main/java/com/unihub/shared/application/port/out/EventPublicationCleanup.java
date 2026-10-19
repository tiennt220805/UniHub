package com.unihub.shared.application.port.out;

import java.time.Instant;

/** Removes outbox rows past their usefulness (database-schema.md §8.2 Retention). */
public interface EventPublicationCleanup {

    /**
     * Deletes the {@code PUBLISHED} rows published strictly before {@code cutoff}. A {@code
     * PENDING} row is never deleted, whatever its age: it has not reached the broker yet.
     *
     * @return how many rows were deleted
     */
    int deletePublishedBefore(Instant cutoff);
}
