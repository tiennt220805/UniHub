package com.unihub.registration.application.port.out;

/**
 * The counters the Phase 8 review of the shared {@code workshops.version} reads (observability.md
 * §4).
 */
public interface SeatClaimMetrics {

    /** One attempt lost the version race ({@code unihub_seat_claim_version_conflicts_total}). */
    void versionConflicted();

    /**
     * Every attempt lost it and the request got 503 ({@code
     * unihub_seat_claim_retries_exhausted_total}).
     */
    void retriesExhausted();
}
