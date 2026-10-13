package com.unihub.user.application.port.out;

/** {@code legacy_sync_errors.reason_code} values (user.md §4, database-schema.md §2.7). */
public enum QuarantineReason {
    MALFORMED_ROW,
    INVALID_FIELD,
    DUPLICATE_IN_FILE
}
