package com.unihub.registration.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerSeatHoldExpiryMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    // Built before the registry exists, as the bean is: composite meters follow later registries.
    private final MicrometerSeatHoldExpiryMetrics metrics = new MicrometerSeatHoldExpiryMetrics();

    @BeforeEach
    void attach() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void detach() {
        Metrics.removeRegistry(meters);
    }

    @Test
    @DisplayName(
            "each wait for the seat lock is one tick of unihub_seat_hold_expiry_lock_unavailable")
    void lockUnavailable_isCounted() {
        metrics.lockUnavailable();
        metrics.lockUnavailable();

        assertThat(meters.get(MicrometerSeatHoldExpiryMetrics.LOCK_UNAVAILABLE).counter().count())
                .isEqualTo(2.0);
        assertThat(MicrometerSeatHoldExpiryMetrics.LOCK_UNAVAILABLE)
                .isEqualTo("unihub.seat.hold.expiry.lock.unavailable");
    }
}
