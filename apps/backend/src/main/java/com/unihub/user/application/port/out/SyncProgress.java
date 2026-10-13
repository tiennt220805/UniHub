package com.unihub.user.application.port.out;

/** Row counts of one batch, or of a whole run (the four {@code legacy_sync_runs} counters). */
public record SyncProgress(int processed, int created, int updated, int quarantined) {

    public static final SyncProgress NONE = new SyncProgress(0, 0, 0, 0);

    public SyncProgress plus(SyncProgress other) {
        return new SyncProgress(
                processed + other.processed,
                created + other.created,
                updated + other.updated,
                quarantined + other.quarantined);
    }
}
