package com.unihub.checkin.infrastructure.out.metrics;

import com.unihub.checkin.application.port.out.CheckInMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Component;

/**
 * Registers on the global registry like the other modules' adapters; Prometheus renders {@code
 * unihub_checkin_scan_clock_ahead_total} and {@code
 * unihub_checkin_scan_resubmission_conflicts_total}.
 */
@Component
class MicrometerCheckInMetrics implements CheckInMetrics {
    private final Counter clockAhead = Metrics.counter("unihub.checkin.scan.clock.ahead");
    private final Counter conflicts = Metrics.counter("unihub.checkin.scan.resubmission.conflicts");

    @Override
    public void deviceClockAhead(int scans) {
        clockAhead.increment(scans);
    }

    @Override
    public void conflictingResubmission() {
        conflicts.increment();
    }
}
