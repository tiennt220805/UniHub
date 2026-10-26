package com.unihub.registration.infrastructure.out.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link SeatLock} contract every adapter must pass (T-LOCK, Phase 2 checklist D4;
 * registration.md §2): the in-process lock in unit tests, the Redisson lock on a Redis container
 * (INV-06, INV-08). Extend this class and return the adapter under test.
 *
 * <p>Both a {@link java.util.concurrent.locks.ReentrantLock} and a Redisson {@code RLock} are
 * reentrant per thread, so every assertion that a lock is free again re-acquires it on a different
 * thread; a same-thread re-acquire would pass even when {@code close()} released nothing.
 */
public abstract class SeatLockContractTest {
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(5);
    private static final Duration NO_WAIT = Duration.ofMillis(50);

    private final ExecutorService threads = Executors.newCachedThreadPool();
    private SeatLock lock;

    protected abstract SeatLock newLock();

    @BeforeEach
    void createLock() {
        lock = newLock();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("two requests for one workshop are never in the critical section together")
    void sameWorkshop_mutuallyExclusive() throws Exception {
        var workshop = WorkshopId.newId();
        var inside = new AtomicInteger();
        var maxInside = new AtomicInteger();
        var all = new CompletableFuture<?>[16];
        for (int i = 0; i < all.length; i++) {
            all[i] =
                    CompletableFuture.runAsync(
                            () -> {
                                try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
                                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                                    pause(5);
                                    inside.decrementAndGet();
                                }
                            },
                            threads);
        }

        CompletableFuture.allOf(all).get(20, TimeUnit.SECONDS);

        assertThat(maxInside.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a held lock on one workshop does not delay another workshop")
    void differentWorkshops_doNotContend() {
        var first = WorkshopId.newId();
        var second = WorkshopId.newId();

        try (var heldFirst = lock.tryAcquire(first, WAIT, LEASE)) {
            // would time out at once if the two shared a lock
            try (var heldSecond = lock.tryAcquire(second, NO_WAIT, LEASE)) {
                assertThat(heldSecond).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("a request that waits out the timeout gets SeatLockUnavailableException")
    void waitTimeout_throwsSeatLockUnavailable() throws Exception {
        var workshop = WorkshopId.newId();
        var taken = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder =
                CompletableFuture.runAsync(
                        () -> {
                            try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
                                taken.countDown();
                                await(release);
                            }
                        },
                        threads);
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> lock.tryAcquire(workshop, NO_WAIT, LEASE))
                .isInstanceOf(SeatLockUnavailableException.class);

        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a closed handle frees the workshop for another thread")
    void close_freesTheLock() {
        var workshop = WorkshopId.newId();
        var held = lock.tryAcquire(workshop, WAIT, LEASE);
        assertThat(acquirableElsewhere(workshop)).isFalse();

        held.close();

        assertThat(acquirableElsewhere(workshop)).isTrue();
    }

    @Test
    @DisplayName("an exception inside the critical section still releases the lock (AC4.4)")
    void exceptionInsideCriticalSection_releasesTheLock() {
        var workshop = WorkshopId.newId();

        assertThatThrownBy(
                        () -> {
                            try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
                                throw new IllegalStateException("the transaction rolled back");
                            }
                        })
                .isInstanceOf(IllegalStateException.class);

        assertThat(acquirableElsewhere(workshop)).isTrue();
    }

    @Test
    @DisplayName("closing a handle twice releases once and never breaks a later holder")
    void close_isIdempotent() throws Exception {
        var workshop = WorkshopId.newId();
        var first = lock.tryAcquire(workshop, WAIT, LEASE);
        first.close();

        // another holder takes over; a second close of the old handle must not release it
        var second = acquireElsewhereAndHold(workshop);
        try {
            first.close();

            assertThat(acquirableElsewhere(workshop)).isFalse();
        } finally {
            second.release();
        }
        assertThat(acquirableElsewhere(workshop)).isTrue();
    }

    /** Takes the lock on another thread and reports whether it got it; releases at once. */
    private boolean acquirableElsewhere(WorkshopId workshop) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            try (var held = lock.tryAcquire(workshop, NO_WAIT, LEASE)) {
                                return true;
                            } catch (SeatLockUnavailableException busy) {
                                return false;
                            }
                        },
                        threads)
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
    }

    /** Holds the lock on another thread until {@link ElsewhereHolder#release()}. */
    private ElsewhereHolder acquireElsewhereAndHold(WorkshopId workshop) throws Exception {
        var taken = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done =
                CompletableFuture.runAsync(
                        () -> {
                            try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
                                taken.countDown();
                                await(release);
                            }
                        },
                        threads);
        assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue();
        return new ElsewhereHolder(release, done);
    }

    private record ElsewhereHolder(CountDownLatch releaseSignal, CompletableFuture<?> done) {
        void release() throws Exception {
            releaseSignal.countDown();
            done.get(5, TimeUnit.SECONDS);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
