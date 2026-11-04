package com.unihub.registration.infrastructure.out.metrics;

import com.unihub.registration.application.port.out.ActiveHolds;
import com.unihub.shared.infrastructure.out.metrics.BackgroundGauges;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code unihub_registration_active_holds} (observability.md §4, Phase 6 checklist D76): seat-hold
 * pressure during a registration spike, read from PostgreSQL every 10 seconds in the background.
 */
@Component
class ActiveHoldsGauge {
    static final String NAME = "unihub.registration.active.holds";

    ActiveHoldsGauge(ActiveHolds holds, MeterRegistry registry, BackgroundGauges gauges) {
        gauges.register(
                registry,
                NAME,
                "Registrations holding a seat and not yet paid or expired",
                null,
                holds::count);
    }
}
