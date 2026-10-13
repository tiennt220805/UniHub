package com.unihub.shared.infrastructure.out.lock;

import com.unihub.shared.application.port.out.JobLock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * {@link JobLock} held in this JVM's memory.
 *
 * <p>Chỉ an toàn single-instance. TODO(INV-09, Phase 3): thay bằng RedissonJobLock.
 *
 * <p>Two pods each hold their own map, so both would run the same job. The Redisson adapter takes
 * its key and lease from distributed-coordination.md §2 and must pass JobLockContractTest on a
 * Redis container.
 *
 * <p>The lease is not enforced: a holder that dies takes the whole JVM, and so the lock, with it.
 * {@link ReentrantLock} is used non-reentrantly to keep the contract identical to the Redis lock:
 * the holder thread asking again is refused. The handle must be closed by the thread that acquired
 * it, which try-with-resources guarantees.
 */
@Component
public class InProcessJobLock implements JobLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration leaseTime) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(leaseTime, "leaseTime");
        var lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return Optional.empty();
        }
        if (lock.getHoldCount() > 1) {
            lock.unlock();
            return Optional.empty();
        }
        return Optional.of(new Handle(lock));
    }

    private static final class Handle implements LockHandle {

        private final ReentrantLock lock;
        private final AtomicBoolean released = new AtomicBoolean();

        private Handle(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                lock.unlock();
            }
        }
    }
}
