package com.unihub.registration.infrastructure.out.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The in-process adapter passes the shared {@link SeatLockContractTest}; what stays here is
 * specific to it: it keeps no entry once everyone is done (AC4.5) and handles an interrupted
 * waiter.
 */
class InProcessSeatLockTest extends SeatLockContractTest {
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(5);

    private final InProcessSeatLock lock = new InProcessSeatLock();
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @Override
    protected SeatLock newLock() {
        return lock;
    }

    @AfterEach
    void stopWorkerThreads() {
        threads.shutdownNow();
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
}
