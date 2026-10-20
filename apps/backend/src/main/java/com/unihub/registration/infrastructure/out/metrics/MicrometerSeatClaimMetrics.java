package com.unihub.registration.infrastructure.out.metrics;

import com.unihub.registration.application.port.out.SeatClaimMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Component;

/**
 * Registers on the global registry (as {@code LoggingAuditTrail} does); Prometheus renders the
 * dotted names with {@code _total}: {@code unihub_seat_claim_version_conflicts_total} and {@code
 * unihub_seat_claim_retries_exhausted_total}.
 */
@Component
class MicrometerSeatClaimMetrics implements SeatClaimMetrics {
    static final String VERSION_CONFLICTS = "unihub.seat.claim.version.conflicts";
    static final String RETRIES_EXHAUSTED = "unihub.seat.claim.retries.exhausted";

    private final Counter versionConflicts = Metrics.counter(VERSION_CONFLICTS);
    private final Counter retriesExhausted = Metrics.counter(RETRIES_EXHAUSTED);

    @Override
    public void versionConflicted() {
        versionConflicts.increment();
    }

    @Override
    public void retriesExhausted() {
        retriesExhausted.increment();
    }
}
