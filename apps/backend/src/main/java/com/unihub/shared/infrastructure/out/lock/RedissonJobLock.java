package com.unihub.shared.infrastructure.out.lock;

import com.unihub.shared.application.port.out.JobLock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link JobLock} in Redis (distributed-coordination.md §2, INV-09): the key and lease come from
 * the caller, one key per job, so a job body never runs on two instances at once.
 *
 * <p><b>Non-blocking.</b> {@code tryLock(0, lease)} either takes the lock now or reports it held; a
 * scheduled tick is not a user waiting, so it skips and trusts the next tick.
 *
 * <p><b>Not reentrant, unlike {@code RLock}.</b> Redisson's lock lets the holder thread take it
 * again; the {@link JobLock} port says a holder asking again gets nothing, so the thread's own hold
 * is checked first.
 *
 * <p><b>Any Redis failure means no lock</b> (checklist D20): refused or hung, the cycle is skipped,
 * never run uncoordinated. Release is holder-only and never throws — a lease that ran out and was
 * taken by another instance is not this holder's to free. A request that timed out while Redis was
 * paused may still be executed when it wakes (seen for the seat lock, RedissonSeatLockIT), which
 * would hold the key until its lease runs out and skip the job meanwhile; for this lock that is
 * possible but not observed. Harmless either way: every job is idempotent and the next cycle runs
 * it.
 *
 * <p>Lock ownership in Redisson is the client id plus the thread, so each application instance must
 * have its own {@code RedissonClient}.
 */
@Component
public class RedissonJobLock implements JobLock {

    private static final Logger LOG = LoggerFactory.getLogger(RedissonJobLock.class);

    private final RedissonClient redis;

    public RedissonJobLock(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration leaseTime) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(leaseTime, "leaseTime");
        var lock = redis.getLock(key);
        try {
            // a Redis round trip, so inside the try
            if (lock.isHeldByCurrentThread()) {
                return Optional.empty();
            }
            if (!lock.tryLock(0, leaseTime.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RedisException redisFailure) {
            LOG.warn("Job lock {} unavailable, cycle skipped: {}", key, redisFailure.toString());
            return Optional.empty();
        }
        return Optional.of(new Handle(lock, key));
    }

    private static final class Handle implements LockHandle {
        private final RLock lock;
        private final String key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(RLock lock, String key) {
            this.lock = lock;
            this.key = key;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException notHeldAnyMore) {
                LOG.warn("Job lock {} was no longer held at release (lease expired)", key);
            } catch (RedisException redisFailure) {
                LOG.warn(
                        "Job lock {} not released: Redis failed; it expires with its lease: {}",
                        key,
                        redisFailure.toString());
            }
        }
    }
}
