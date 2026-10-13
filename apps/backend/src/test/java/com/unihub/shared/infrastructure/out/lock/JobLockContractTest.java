package com.unihub.shared.infrastructure.out.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.application.port.out.JobLock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link JobLock} contract every adapter must pass: the in-process lock now, the Redisson lock
 * in Phase 3 (INV-09) — extend this class and return the adapter under test.
 */
public abstract class JobLockContractTest {

    private static final String KEY = "unihub:lock:job:contract-test";
    private static final String OTHER_KEY = "unihub:lock:job:contract-test-other";
    private static final Duration LEASE = Duration.ofMinutes(1);

    private JobLock lock;

    protected abstract JobLock newLock();

    @BeforeEach
    void createLock() {
        lock = newLock();
    }

    @Test
    @DisplayName("a free key is acquired")
    void freeKey_isAcquired() {
        var handle = lock.tryAcquire(KEY, LEASE);

        assertThat(handle).isPresent();
        handle.get().close();
    }

    @Test
    @DisplayName("a held key is refused, even to the thread holding it")
    void heldKey_isRefusedToTheHolder() {
        try (var held = lock.tryAcquire(KEY, LEASE).orElseThrow()) {
            assertThat(lock.tryAcquire(KEY, LEASE)).isEmpty();
        }
    }

    @Test
    @DisplayName("a held key is refused to another thread, without waiting")
    void heldKey_isRefusedToAnotherThread() {
        try (var held = lock.tryAcquire(KEY, LEASE).orElseThrow()) {
            var otherThread = CompletableFuture.supplyAsync(() -> lock.tryAcquire(KEY, LEASE));

            assertThat(otherThread.join()).isEmpty();
        }
    }

    @Test
    @DisplayName("different keys never block each other")
    void differentKeys_areIndependent() {
        try (var held = lock.tryAcquire(KEY, LEASE).orElseThrow()) {
            var other = lock.tryAcquire(OTHER_KEY, LEASE);

            assertThat(other).isPresent();
            other.get().close();
        }
    }

    @Test
    @DisplayName("a released key can be acquired again")
    void releasedKey_canBeReacquired() {
        lock.tryAcquire(KEY, LEASE).orElseThrow().close();

        var again = lock.tryAcquire(KEY, LEASE);

        assertThat(again).isPresent();
        again.get().close();
    }

    @Test
    @DisplayName("closing a handle twice releases once and does not throw")
    void closingTwice_isHarmless() {
        var handle = lock.tryAcquire(KEY, LEASE).orElseThrow();
        handle.close();
        handle.close();

        var again = lock.tryAcquire(KEY, LEASE);

        assertThat(again).isPresent();
        again.get().close();
    }
}
