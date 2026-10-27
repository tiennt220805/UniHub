package com.unihub.registration.infrastructure.out.metrics;

import com.unihub.registration.application.port.out.SeatHoldExpiryMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Component;

/**
 * Registers on the global registry, like {@link MicrometerSeatClaimMetrics}; Prometheus renders the
 * dotted name with {@code _total}: {@code unihub_seat_hold_expiry_lock_unavailable_total}.
 */
@Component
class MicrometerSeatHoldExpiryMetrics implements SeatHoldExpiryMetrics {
    static final String LOCK_UNAVAILABLE = "unihub.seat.hold.expiry.lock.unavailable";

    private final Counter lockUnavailable = Metrics.counter(LOCK_UNAVAILABLE);

    @Override
    public void lockUnavailable() {
        lockUnavailable.increment();
    }
}
