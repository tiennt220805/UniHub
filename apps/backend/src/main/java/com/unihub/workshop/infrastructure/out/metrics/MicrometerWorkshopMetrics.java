package com.unihub.workshop.infrastructure.out.metrics;

import com.unihub.workshop.application.port.out.WorkshopMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Component;

/**
 * Registers on the global registry, as {@code LoggingAuditTrail} does: until the observability
 * stack exists the counter is a no-op. Prometheus renders the dotted name with a {@code _total}
 * suffix: {@code unihub_workshop_edit_conflicts_total}.
 */
@Component
class MicrometerWorkshopMetrics implements WorkshopMetrics {
    static final String EDIT_CONFLICTS = "unihub.workshop.edit.conflicts";

    private final Counter editConflicts = Metrics.counter(EDIT_CONFLICTS);

    @Override
    public void editConflicted() {
        editConflicts.increment();
    }
}
