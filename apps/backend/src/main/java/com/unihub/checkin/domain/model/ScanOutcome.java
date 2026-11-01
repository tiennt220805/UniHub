package com.unihub.checkin.domain.model;

/** {@code check_in_scans.outcome} (database-schema.md §6.1). */
public enum ScanOutcome {
    ACCEPTED,
    REJECTED_DUPLICATE,
    REJECTED_INVALID
}
