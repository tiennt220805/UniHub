package com.unihub.payment.infrastructure.in.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code unihub.jobs.payment-reconciliation.*}: {@code interval} is the pause between sweeps. */
@ConfigurationProperties(prefix = "unihub.jobs.payment-reconciliation")
record PaymentReconciliationProperties(Duration interval) {

    PaymentReconciliationProperties {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException(
                    "unihub.jobs.payment-reconciliation.interval required");
        }
    }
}
