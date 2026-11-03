package com.unihub.shared.infrastructure.in.scheduling;

import com.unihub.shared.application.port.in.PurgeEventPublicationsUseCase;
import com.unihub.shared.application.port.out.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trigger only: takes the job lock, sweeps published outbox rows, releases the lock. On by default
 * — a duplicate run only finds nothing left to delete (distributed-coordination.md §2) — and
 * switched off with {@code unihub.jobs.event-publication-cleanup.enabled=false}.
 */
@Component
@EnableConfigurationProperties(EventPublicationCleanupProperties.class)
@ConditionalOnProperty(
        prefix = "unihub.jobs.event-publication-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class EventPublicationCleanupScheduler {

    /** distributed-coordination.md §2, §5. */
    static final String LOCK_KEY = "unihub:lock:job:event-publication-cleanup";

    static final Duration LEASE = Duration.ofMinutes(1);

    private static final Logger LOG =
            LoggerFactory.getLogger(EventPublicationCleanupScheduler.class);

    private final JobLock jobLock;
    private final PurgeEventPublicationsUseCase purge;
    private final EventPublicationCleanupProperties properties;
    private final JobCycle cycles;

    EventPublicationCleanupScheduler(
            JobLock jobLock,
            PurgeEventPublicationsUseCase purge,
            EventPublicationCleanupProperties properties,
            JobCycle cycles) {
        this.jobLock = jobLock;
        this.purge = purge;
        this.properties = properties;
        this.cycles = cycles;
    }

    @Scheduled(
            fixedDelayString = "${unihub.jobs.event-publication-cleanup.interval}",
            initialDelayString = "${unihub.jobs.event-publication-cleanup.interval}")
    public void sweep() {
        cycles.run("event-publication-cleanup", this::cycle);
    }

    private void cycle() {
        var lock = jobLock.tryAcquire(LOCK_KEY, LEASE);
        if (lock.isEmpty()) {
            LOG.debug("Event publication cleanup skipped: job lock {} is held", LOCK_KEY);
            return;
        }
        try (var held = lock.get()) {
            int deleted = purge.purge(properties.retention());
            if (deleted > 0) {
                LOG.info("Event publication cleanup deleted {} published rows", deleted);
            }
        }
    }
}
