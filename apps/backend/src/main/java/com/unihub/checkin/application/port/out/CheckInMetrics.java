package com.unihub.checkin.application.port.out;

/** Counters for what sync accepts but an operator should be able to see (checkin.md §8). */
public interface CheckInMetrics {

    /**
     * Scans stamped more than five minutes after the server's clock: not refused (that would lose
     * data), counted ({@code unihub_checkin_scan_clock_ahead_total}).
     */
    void deviceClockAhead(int scans);

    /**
     * A scan id sent again with other content than the stored row ({@code
     * unihub_checkin_scan_resubmission_conflicts_total}).
     */
    void conflictingResubmission();
}
