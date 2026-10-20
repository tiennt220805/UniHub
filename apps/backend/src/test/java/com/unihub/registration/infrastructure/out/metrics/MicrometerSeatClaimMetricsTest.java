package com.unihub.registration.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerSeatClaimMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void attach() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void detach() {
        Metrics.removeRegistry(meters);
    }

    private double count(String name) {
        return meters.get(name).counter().count();
    }

    @Test
    @DisplayName("version conflicts and exhausted retries are counted on separate counters")
    void conflictAndExhaustion_counted() {
        var metrics = new MicrometerSeatClaimMetrics();

        metrics.versionConflicted();
        metrics.versionConflicted();
        metrics.retriesExhausted();

        assertThat(count(MicrometerSeatClaimMetrics.VERSION_CONFLICTS)).isEqualTo(2.0);
        assertThat(count(MicrometerSeatClaimMetrics.RETRIES_EXHAUSTED)).isEqualTo(1.0);
    }
}
