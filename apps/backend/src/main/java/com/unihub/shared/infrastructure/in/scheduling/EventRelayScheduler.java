package com.unihub.shared.infrastructure.in.scheduling;

import com.unihub.shared.application.port.in.RelayPendingEventsUseCase;
import com.unihub.shared.application.port.out.JobLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trigger only (backend-coding-standards.md §9.4, §8.3): takes the job lock, runs one relay cycle,
 * releases the lock. A cycle that finds the lock taken is skipped — rows stay {@code PENDING} and
 * go out next cycle. On by default, since without it no event ever leaves the outbox; {@code
 * unihub.jobs.outbox-relay.enabled=false} switches it off.
 */
@Component
@EnableConfigurationProperties(OutboxRelayProperties.class)
@ConditionalOnProperty(
        prefix = "unihub.jobs.outbox-relay",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class EventRelayScheduler {

    /** distributed-coordination.md §2, §5; messaging.md §5. */
    static final String LOCK_KEY = "unihub:lock:job:outbox-relay";

    static final Duration LEASE = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(EventRelayScheduler.class);

    private final JobLock jobLock;
    private final RelayPendingEventsUseCase relay;
    private final OutboxRelayProperties properties;
    private final JobCycle cycles;

    EventRelayScheduler(
            JobLock jobLock,
            RelayPendingEventsUseCase relay,
            OutboxRelayProperties properties,
            JobCycle cycles) {
        this.jobLock = jobLock;
        this.relay = relay;
        this.properties = properties;
        this.cycles = cycles;
    }

    @Scheduled(fixedDelayString = "${unihub.jobs.outbox-relay.interval}")
    public void run() {
        cycles.run("outbox-relay", this::cycle);
    }

    private void cycle() {
        var lock = jobLock.tryAcquire(LOCK_KEY, LEASE);
        if (lock.isEmpty()) {
            LOG.debug("Outbox relay skipped: job lock {} is held", LOCK_KEY);
            return;
        }
        try (var held = lock.get()) {
            int published = relay.relay(properties.batchSize());
            if (published > 0) {
                LOG.debug("Outbox relay published {} events", published);
            }
        } catch (RuntimeException e) {
            // A database error must not kill the schedule; the next cycle retries the same rows.
            LOG.warn("Outbox relay cycle failed: {}", e.toString());
        }
    }
}
