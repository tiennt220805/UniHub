package com.unihub.user.infrastructure.in.scheduling;

import com.unihub.shared.application.port.out.JobLock;
import com.unihub.shared.infrastructure.in.scheduling.JobCycle;
import com.unihub.user.application.port.in.RunNightlySyncUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trigger only (backend-coding-standards.md §9.4): takes the job lock, runs the sync, releases the
 * lock. A cycle that finds the lock taken is skipped, not queued (architecture.md §4.6). Exists
 * only when {@code unihub.jobs.nightly-sync.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "unihub.jobs.nightly-sync", name = "enabled", havingValue = "true")
class NightlySyncScheduler {

    /** distributed-coordination.md §2, §5. */
    static final String LOCK_KEY = "unihub:lock:job:nightly-student-sync";

    static final Duration LEASE = Duration.ofMinutes(30);

    private static final Logger LOG = LoggerFactory.getLogger(NightlySyncScheduler.class);

    private final JobLock jobLock;
    private final RunNightlySyncUseCase runNightlySync;
    private final JobCycle cycles;

    NightlySyncScheduler(JobLock jobLock, RunNightlySyncUseCase runNightlySync, JobCycle cycles) {
        this.jobLock = jobLock;
        this.runNightlySync = runNightlySync;
        this.cycles = cycles;
    }

    @Scheduled(cron = "${unihub.jobs.nightly-sync.cron}", zone = "${unihub.jobs.nightly-sync.zone}")
    public void runNightlySync() {
        cycles.run("nightly-sync", this::cycle);
    }

    private void cycle() {
        var lock = jobLock.tryAcquire(LOCK_KEY, LEASE);
        if (lock.isEmpty()) {
            LOG.warn("Nightly sync skipped: job lock {} is held by another run", LOCK_KEY);
            return;
        }
        try (var held = lock.get()) {
            runNightlySync.run();
        }
    }
}
