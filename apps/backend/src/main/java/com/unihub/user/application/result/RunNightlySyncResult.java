package com.unihub.user.application.result;

import java.time.LocalDate;
import java.util.UUID;

/** How one sync cycle ended. Either way, exactly one {@code legacy_sync_runs} row records it. */
public sealed interface RunNightlySyncResult {

    UUID runId();

    LocalDate exportDate();

    /** Every row was either upserted or quarantined. */
    record Completed(
            UUID runId,
            LocalDate exportDate,
            int rowsProcessed,
            int rowsCreated,
            int rowsUpdated,
            int rowsQuarantined)
            implements RunNightlySyncResult {}

    /**
     * The run stopped; {@code failureReason} is a {@code legacy_sync_runs.failure_reason} code.
     * Batches committed before a {@code PIPELINE_ERROR} stay committed.
     */
    record Failed(UUID runId, LocalDate exportDate, String failureReason, String failureDetail)
            implements RunNightlySyncResult {}
}
