package com.unihub.user.application.port.out;

/** {@code legacy_sync_runs.failure_reason} values (database-schema.md §2.6). */
public enum SyncFailureReason {
    FILE_NOT_FOUND,
    FILE_UNREADABLE,
    PIPELINE_ERROR
}
