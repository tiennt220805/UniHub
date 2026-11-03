package com.unihub.shared.infrastructure.in.scheduling;

import com.unihub.shared.application.port.in.PurgeIdempotencyKeysUseCase;
import com.unihub.shared.application.port.out.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trigger only (backend-coding-standards.md §9.4): takes the job lock, runs the sweep, releases the
 * lock. A cycle that finds the lock taken is skipped. On by default — a duplicate run only finds
 * nothing left to delete (distributed-coordination.md §2) — and switched off with {@code
 * unihub.jobs.idempotency-cleanup.enabled=false}.
 */
@Component
@EnableConfigurationProperties(IdempotencyCleanupProperties.class)
@ConditionalOnProperty(
        prefix = "unihub.jobs.idempotency-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class IdempotencyCleanupScheduler {

    /** distributed-coordination.md §2, §5. */
    static final String LOCK_KEY = "unihub:lock:job:idempotency-cleanup";

    static final Duration LEASE = Duration.ofMinutes(1);

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    private final JobLock jobLock;
    private final PurgeIdempotencyKeysUseCase purge;
    private final IdempotencyCleanupProperties properties;
    private final JobCycle cycles;

    IdempotencyCleanupScheduler(
            JobLock jobLock,
            PurgeIdempotencyKeysUseCase purge,
            IdempotencyCleanupProperties properties,
            JobCycle cycles) {
        this.jobLock = jobLock;
        this.purge = purge;
        this.properties = properties;
        this.cycles = cycles;
    }

    @Scheduled(
            fixedDelayString = "${unihub.jobs.idempotency-cleanup.interval}",
            initialDelayString = "${unihub.jobs.idempotency-cleanup.interval}")
    public void sweep() {
        cycles.run("idempotency-cleanup", this::cycle);
    }

    private void cycle() {
        var lock = jobLock.tryAcquire(LOCK_KEY, LEASE);
        if (lock.isEmpty()) {
            LOG.debug("Idempotency cleanup skipped: job lock {} is held", LOCK_KEY);
            return;
        }
        try (var held = lock.get()) {
            int deleted = purge.purge(properties.retention());
            if (deleted > 0) {
                LOG.info("Idempotency cleanup deleted {} expired keys", deleted);
            }
        }
    }
}
