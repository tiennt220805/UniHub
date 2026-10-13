package com.unihub.user.infrastructure.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.JobLock;
import com.unihub.shared.infrastructure.out.lock.InProcessJobLock;
import com.unihub.user.application.port.in.RunNightlySyncUseCase;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The trigger runs the sync only while holding the job lock, and always gives the lock back. */
class NightlySyncSchedulerTest {

    private final JobLock jobLock = new InProcessJobLock();
    private final RunNightlySyncUseCase useCase = mock(RunNightlySyncUseCase.class);
    private final NightlySyncScheduler scheduler = new NightlySyncScheduler(jobLock, useCase);

    @Test
    @DisplayName("uses the documented lock key and lease")
    void lockKeyAndLease() {
        assertThat(NightlySyncScheduler.LOCK_KEY).isEqualTo("unihub:lock:job:nightly-student-sync");
        assertThat(NightlySyncScheduler.LEASE).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("runs the sync and releases the lock afterwards")
    void runsAndReleases() {
        scheduler.runNightlySync();

        verify(useCase).run();
        assertLockIsFree();
    }

    @Test
    @DisplayName("skips the cycle without throwing when the lock is already held")
    void lockHeld_skipsWithoutThrowing() {
        try (var held =
                jobLock.tryAcquire(NightlySyncScheduler.LOCK_KEY, Duration.ofMinutes(1))
                        .orElseThrow()) {
            scheduler.runNightlySync();
        }

        verify(useCase, never()).run();
    }

    @Test
    @DisplayName("releases the lock even when the sync throws")
    void syncThrows_lockStillReleased() {
        when(useCase.run()).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(scheduler::runNightlySync).hasMessage("database down");

        assertLockIsFree();
    }

    @Test
    @DisplayName("two triggers firing at once run the sync exactly once")
    void concurrentTriggers_runOnce() throws Exception {
        var release = new CountDownLatch(1);
        when(useCase.run())
                .thenAnswer(
                        invocation -> {
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            return null;
                        });
        var start = new CyclicBarrier(2);
        Runnable trigger =
                () -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    scheduler.runNightlySync();
                };

        var first = CompletableFuture.runAsync(trigger);
        var second = CompletableFuture.runAsync(trigger);
        // The trigger that lost the lock returns at once; the winner is still inside the sync.
        CompletableFuture.anyOf(first, second).get(5, TimeUnit.SECONDS);
        release.countDown();
        CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);

        verify(useCase, times(1)).run();
        assertLockIsFree();
    }

    private void assertLockIsFree() {
        var again = jobLock.tryAcquire(NightlySyncScheduler.LOCK_KEY, Duration.ofMinutes(1));
        assertThat(again).isPresent();
        again.get().close();
    }
}
