package com.unihub.user.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerSyncMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerSyncMetrics metrics = new MicrometerSyncMetrics(registry);

    private double ratio() {
        return registry.get(MicrometerSyncMetrics.QUARANTINE_RATIO).gauge().value();
    }

    @Test
    @DisplayName("the series exists before any run and says unknown, not 0")
    void unknownUntilARunCompletes() {
        assertThat(ratio()).isNaN();
    }

    @Test
    @DisplayName("a completed run sets quarantined / processed")
    void ratioOfTheLastRun() {
        metrics.runCompleted(8, 6);
        assertThat(ratio()).isEqualTo(0.75);

        metrics.runCompleted(200, 1);
        assertThat(ratio()).isEqualTo(0.005);
    }

    @Test
    @DisplayName("a run with no rows is 0, not a division by zero")
    void emptyRunIsZero() {
        metrics.runCompleted(0, 0);
        assertThat(ratio()).isZero();
    }
}
