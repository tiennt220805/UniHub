package com.unihub.registration.infrastructure.out.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-LOCK (Phase 2 checklist D4): AC4.1, AC4.2, AC4.5. */
class InProcessSeatLockTest {
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(5);

    private final InProcessSeatLock lock = new InProcessSeatLock();
    private final ExecutorService threads = Executors.newCachedThreadPool();

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

        CompletableFuture.allOf(all).get(10, TimeUnit.SECONDS);

        assertThat(maxInside.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a held lock on one workshop does not delay another workshop")
    void differentWorkshops_doNotContend() {
        var first = WorkshopId.newId();
        var second = WorkshopId.newId();

        try (var heldFirst = lock.tryAcquire(first, WAIT, LEASE)) {
            // would time out at once if the two shared a lock
            try (var heldSecond = lock.tryAcquire(second, Duration.ofMillis(50), LEASE)) {
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

        assertThatThrownBy(() -> lock.tryAcquire(workshop, Duration.ofMillis(50), LEASE))
                .isInstanceOf(SeatLockUnavailableException.class);

        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("after every holder and waiter is done, the adapter keeps no entry")
    void entriesRemovedAfterRelease() throws Exception {
        var workshop = WorkshopId.newId();
        var all = new CompletableFuture<?>[8];
        for (int i = 0; i < all.length; i++) {
            all[i] =
                    CompletableFuture.runAsync(
                            () -> {
                                try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
                                    pause(2);
                                }
                            },
                            threads);
        }
        CompletableFuture.allOf(all).get(10, TimeUnit.SECONDS);
        // and a request that timed out must not leave its entry behind either
        try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
            assertThatThrownBy(
                            () ->
                                    CompletableFuture.supplyAsync(
                                                    () ->
                                                            lock.tryAcquire(
                                                                    workshop,
                                                                    Duration.ofMillis(20),
                                                                    LEASE),
                                                    threads)
                                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SeatLockUnavailableException.class);
        }

        assertThat(lock.size()).isZero();
    }

    @Test
    @DisplayName("closing a handle twice releases once and never breaks a later holder")
    void close_isIdempotent() {
        var workshop = WorkshopId.newId();
        var first = lock.tryAcquire(workshop, WAIT, LEASE);

        first.close();
        first.close();

        try (var second = lock.tryAcquire(workshop, Duration.ofMillis(50), LEASE)) {
            assertThat(second).isNotNull();
        }
        assertThat(lock.size()).isZero();
    }

    @Test
    @DisplayName("an interrupted waiter gives up as unavailable and keeps its interrupt flag")
    void interrupted_isUnavailable() throws Exception {
        var workshop = WorkshopId.newId();
        var outcome = new CompletableFuture<Boolean>();
        try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
            var waiter =
                    new Thread(
                            () -> {
                                try {
                                    lock.tryAcquire(workshop, WAIT, LEASE);
                                    outcome.complete(false);
                                } catch (SeatLockUnavailableException e) {
                                    outcome.complete(Thread.currentThread().isInterrupted());
                                }
                            });
            waiter.start();
            pause(100);
            waiter.interrupt();

            assertThat(outcome.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(lock.size()).isZero();
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
