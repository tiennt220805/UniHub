package com.unihub.user.infrastructure.out.metrics;

import com.unihub.user.application.port.out.SyncMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * {@code unihub_legacy_sync_last_run_quarantine_ratio}, 0.0–1.0 (observability.md §4). Registered
 * at startup as NaN: the job is off by default and runs once a night, so until the first completed
 * run the series exists and says "unknown" instead of being absent. A restart forgets the value
 * until the next night's run (the job runs on one pod, and the ratio is that run's, not a rolling
 * figure); the run's counts stay in {@code legacy_sync_runs}.
 */
@Component
class MicrometerSyncMetrics implements SyncMetrics {
    static final String QUARANTINE_RATIO = "unihub.legacy.sync.last.run.quarantine.ratio";

    private final AtomicLong ratioBits = new AtomicLong(Double.doubleToLongBits(Double.NaN));

    MicrometerSyncMetrics(MeterRegistry registry) {
        Gauge.builder(QUARANTINE_RATIO, ratioBits, bits -> Double.longBitsToDouble(bits.get()))
                .description(
                        "Share of the last completed nightly sync's rows that were quarantined")
                .strongReference(true)
                .register(registry);
    }

    @Override
    public void runCompleted(int rowsProcessed, int rowsQuarantined) {
        double ratio = rowsProcessed <= 0 ? 0.0 : (double) rowsQuarantined / rowsProcessed;
        ratioBits.set(Double.doubleToLongBits(ratio));
    }
}
