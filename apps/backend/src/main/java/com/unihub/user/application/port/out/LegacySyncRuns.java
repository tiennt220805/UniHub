package com.unihub.user.application.port.out;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Bookkeeping for {@code legacy_sync_runs} (user.md §5). Every call commits on its own unless it
 * runs inside a caller's transaction.
 */
public interface LegacySyncRuns {

    /** Records a new {@code RUNNING} run and returns its id. */
    UUID start(LocalDate exportDate);

    /** Adds one committed batch's counts to the run's counters. */
    void recordProgress(UUID runId, SyncProgress batch);

    /** {@code RUNNING → COMPLETED}. */
    void complete(UUID runId);

    /** {@code RUNNING → FAILED} with a reason code and, when known, free-text detail. */
    void fail(UUID runId, SyncFailureReason reason, String detail);
}
