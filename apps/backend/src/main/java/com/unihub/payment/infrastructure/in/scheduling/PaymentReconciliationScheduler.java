package com.unihub.payment.infrastructure.in.scheduling;

import com.unihub.payment.application.port.in.RequestPaymentReconciliationsUseCase;
import com.unihub.shared.application.port.out.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trigger only (backend-coding-standards.md §9.4): takes the job lock, asks for the reconciliation
 * requests, releases the lock. A cycle that finds the lock taken is skipped. On by default — a
 * duplicate run only requests payments the consumer then finds resolved (payment.md §6,
 * distributed-coordination.md §2) — and switched off with {@code
 * unihub.jobs.payment-reconciliation.enabled=false}.
 */
@Component
@EnableConfigurationProperties(PaymentReconciliationProperties.class)
@ConditionalOnProperty(
        prefix = "unihub.jobs.payment-reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class PaymentReconciliationScheduler {

    /** distributed-coordination.md §2, §5. */
    static final String LOCK_KEY = "unihub:lock:job:payment-reconciliation";

    static final Duration LEASE = Duration.ofMinutes(5);

    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);

    private final JobLock jobLock;
    private final RequestPaymentReconciliationsUseCase sweep;

    PaymentReconciliationScheduler(JobLock jobLock, RequestPaymentReconciliationsUseCase sweep) {
        this.jobLock = jobLock;
        this.sweep = sweep;
    }

    @Scheduled(
            fixedDelayString = "${unihub.jobs.payment-reconciliation.interval}",
            initialDelayString = "${unihub.jobs.payment-reconciliation.interval}")
    public void sweep() {
        var lock = jobLock.tryAcquire(LOCK_KEY, LEASE);
        if (lock.isEmpty()) {
            LOG.debug("Payment reconciliation skipped: job lock {} is held", LOCK_KEY);
            return;
        }
        try (var held = lock.get()) {
            int requested = sweep.requestReconciliations();
            if (requested > 0) {
                LOG.info("Payment reconciliation requested for {} stale payments", requested);
            }
        }
    }
}
