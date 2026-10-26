package com.unihub.registration.infrastructure.out.lock;

import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.port.out.SeatLockHandle;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Test fixture {@link SeatLock}: one JVM lock per workshop. It never ships — production uses {@code
 * RedissonSeatLock} (Phase 3 checklist D21) — and serves two purposes: the in-process side of the
 * contract test, and the control of the multi-instance harness, where two contexts built on this
 * lock must show overlapping holds, proving the harness really separates the instances (M-32). It
 * does NOT protect across instances.
 *
 * <p>{@code leaseTime} cannot apply to a JVM lock and is ignored. A {@link ReentrantLock} must be
 * unlocked by the thread that locked it, so acquire and close happen on the request thread.
 *
 * <p>Entries are reference-counted inside {@link ConcurrentHashMap#compute}: counted on entry to
 * {@code tryAcquire} (before waiting), uncounted on close or timeout, and removed when the count
 * reaches zero. Counting before waiting is what stops a waiter from holding a lock object that was
 * already evicted, which would let two threads lock different objects for one workshop.
 */
public class InProcessSeatLock implements SeatLock {

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users; // touched only inside ConcurrentHashMap.compute for its key
    }

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public SeatLockHandle tryAcquire(
            WorkshopId workshopId, Duration waitTimeout, Duration leaseTime) {
        var key = workshopId.value();
        var entry =
                entries.compute(
                        key,
                        (k, existing) -> {
                            var e = existing == null ? new Entry() : existing;
                            e.users++;
                            return e;
                        });
        var acquired = false;
        try {
            acquired = entry.lock.tryLock(waitTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            uncount(key);
            throw new SeatLockUnavailableException(workshopId);
        }
        return new Handle(key, entry);
    }

    /** Number of workshops with a holder or a waiter; zero once everyone is done (AC4.5). */
    public int size() {
        return entries.size();
    }

    private void uncount(UUID key) {
        entries.compute(key, (k, e) -> e == null || --e.users <= 0 ? null : e);
    }

    private final class Handle implements SeatLockHandle {
        private final UUID key;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        Handle(UUID key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                entry.lock.unlock();
                uncount(key);
            }
        }
    }
}
