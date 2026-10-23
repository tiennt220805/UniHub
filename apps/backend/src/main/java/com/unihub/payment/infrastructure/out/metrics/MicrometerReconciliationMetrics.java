package com.unihub.payment.infrastructure.out.metrics;

import com.unihub.payment.application.port.out.ReconciliationMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Component;

/**
 * Registers on the global registry (as {@code MicrometerSeatClaimMetrics} does); Prometheus renders
 * the dotted name with {@code _total}: {@code unihub_payment_reconciliation_stuck_total}.
 */
@Component
class MicrometerReconciliationMetrics implements ReconciliationMetrics {
    static final String STUCK = "unihub.payment.reconciliation.stuck";

    private final Counter stuck = Metrics.counter(STUCK);

    @Override
    public void stuck() {
        stuck.increment();
    }
}
