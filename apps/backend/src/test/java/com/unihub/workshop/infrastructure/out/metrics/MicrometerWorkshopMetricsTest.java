package com.unihub.workshop.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerWorkshopMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void attach() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void detach() {
        Metrics.removeRegistry(meters);
    }

    @Test
    @DisplayName("each refused organizer edit increments unihub.workshop.edit.conflicts")
    void editConflicted_counts() {
        var metrics = new MicrometerWorkshopMetrics();

        metrics.editConflicted();
        metrics.editConflicted();

        assertThat(meters.get(MicrometerWorkshopMetrics.EDIT_CONFLICTS).counter().count())
                .isEqualTo(2.0);
    }
}
