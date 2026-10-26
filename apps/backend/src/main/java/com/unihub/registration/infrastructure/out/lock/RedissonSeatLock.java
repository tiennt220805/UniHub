package com.unihub.registration.infrastructure.out.lock;

import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.port.out.SeatLockHandle;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link SeatLock} in Redis: one lock per workshop, key {@code unihub:lock:seat:{workshopId}}
 * (registration.md §2, distributed-coordination.md §5). It is advisory — it decides who may
 * <em>attempt</em> the write; the version and CHECK in PostgreSQL decide whether it is accepted.
 *
 * <p><b>Fixed lease, no watchdog.</b> Passing an explicit lease to {@code tryLock} is what turns
 * Redisson's auto-renewal off: a holder that stalls loses the lock after the lease, so a crashed
 * pod never blocks a workshop for longer than that.
 *
 * <p><b>Any Redis failure means no lock</b> (checklist D20, INV-08): a refused connection and a
 * hung one (a {@code RedisTimeoutException} after the shared client's command timeout) both end as
 * {@link SeatLockUnavailableException}, so the write is rejected and never proceeds uncoordinated.
 * A request that timed out against a hung Redis may still have its acquire executed when Redis
 * wakes; that orphaned lock lives only until its lease, which is why the lease is fixed and short.
 *
 * <p><b>Release is holder-only and never throws.</b> The lease may already have run out and the
 * lock been taken by another pod; Redisson's {@code unlock} refuses to release a lock this client
 * and thread no longer hold, and that refusal is logged, not raised — close runs after the commit,
 * and an exception there would turn an accepted seat into a 500.
 */
@Component
class RedissonSeatLock implements SeatLock {

    static final String KEY_PREFIX = "unihub:lock:seat:";

    private static final Logger LOG = LoggerFactory.getLogger(RedissonSeatLock.class);

    private final RedissonClient redis;

    RedissonSeatLock(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public SeatLockHandle tryAcquire(
            WorkshopId workshopId, Duration waitTimeout, Duration leaseTime) {
        var lock = redis.getLock(KEY_PREFIX + workshopId.value());
        boolean acquired;
        try {
            acquired =
                    lock.tryLock(
                            waitTimeout.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SeatLockUnavailableException(workshopId);
        } catch (RedisException redisFailure) {
            LOG.warn(
                    "Seat lock for workshop {} unavailable: Redis failed: {}",
                    workshopId.value(),
                    redisFailure.toString());
            throw new SeatLockUnavailableException(workshopId);
        }
        if (!acquired) {
            throw new SeatLockUnavailableException(workshopId);
        }
        return new Handle(lock, workshopId);
    }

    private static final class Handle implements SeatLockHandle {
        private final RLock lock;
        private final WorkshopId workshopId;
        private final AtomicBoolean closed = new AtomicBoolean();

        Handle(RLock lock, WorkshopId workshopId) {
            this.lock = lock;
            this.workshopId = workshopId;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException notHeldAnyMore) {
                LOG.warn(
                        "Seat lock for workshop {} was no longer held at release (lease expired)",
                        workshopId.value());
            } catch (RedisException redisFailure) {
                LOG.warn(
                        "Seat lock for workshop {} not released: Redis failed; it expires with its lease: {}",
                        workshopId.value(),
                        redisFailure.toString());
            }
        }
    }
}
