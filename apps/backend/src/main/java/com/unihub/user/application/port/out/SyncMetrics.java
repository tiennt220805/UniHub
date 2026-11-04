package com.unihub.user.application.port.out;

/** What the nightly sync tells the operator about data quality (observability.md §4). */
public interface SyncMetrics {

    /**
     * A run reached {@code COMPLETED}: sets {@code unihub_legacy_sync_last_run_quarantine_ratio} to
     * {@code rowsQuarantined / rowsProcessed}, or 0 for a run that processed no rows. Never throws.
     */
    void runCompleted(int rowsProcessed, int rowsQuarantined);
}
