package com.unihub.shared.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * The scheduled-job coordination lock (architecture.md §4.6, distributed-coordination.md §2): one
 * key per job, so a cycle runs on at most one holder at a time. Advisory only — every job's own
 * writes must stay safe under a duplicate run regardless (user.md §8).
 *
 * <p>Contract, shared by every adapter (JobLockContractTest):
 *
 * <ul>
 *   <li>{@link #tryAcquire} never waits: a key already held returns empty, including when the
 *       caller itself is the holder (not reentrant).
 *   <li>Different keys never block each other.
 *   <li>Once the handle is closed the key can be acquired again.
 * </ul>
 */
public interface JobLock {

    /**
     * Takes the lock for {@code key} if it is free right now.
     *
     * @param leaseTime how long the lock may be held before it expires on its own (key and lease
     *     per job: distributed-coordination.md §5)
     * @return the held lock, or empty when another holder has it
     */
    Optional<LockHandle> tryAcquire(String key, Duration leaseTime);

    /** A held lock. Closing it releases the key; closing it again does nothing. */
    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }
}
