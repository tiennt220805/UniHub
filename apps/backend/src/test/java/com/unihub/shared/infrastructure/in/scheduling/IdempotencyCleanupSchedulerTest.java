package com.unihub.shared.infrastructure.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.application.port.in.PurgeIdempotencyKeysUseCase;
import com.unihub.shared.infrastructure.out.lock.InProcessJobLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyCleanupSchedulerTest {

    private static final Duration RETENTION = Duration.ofMinutes(30);

    private final InProcessJobLock lock = new InProcessJobLock();
    private final List<Duration> purged = new ArrayList<>();
    private final PurgeIdempotencyKeysUseCase purge =
            retention -> {
                purged.add(retention);
                return 0;
            };
    private final IdempotencyCleanupScheduler scheduler =
            new IdempotencyCleanupScheduler(
                    lock,
                    purge,
                    new IdempotencyCleanupProperties(Duration.ofMinutes(1), RETENTION));

    @Test
    @DisplayName("runs the sweep with the configured retention under the job lock")
    void sweep_runsUnderTheLock() {
        scheduler.sweep();

        assertThat(purged).containsExactly(RETENTION);
        assertThat(lock.tryAcquire(IdempotencyCleanupScheduler.LOCK_KEY, Duration.ofMinutes(1)))
                .as("lock released after the sweep")
                .isPresent();
    }

    @Test
    @DisplayName("skips the cycle when another holder has the lock")
    void sweep_skippedWhenLockHeld() {
        try (var held =
                lock.tryAcquire(IdempotencyCleanupScheduler.LOCK_KEY, Duration.ofMinutes(1))
                        .orElseThrow()) {
            scheduler.sweep();
        }

        assertThat(purged).isEmpty();
    }

    @Test
    @DisplayName("releases the lock even when the sweep fails")
    void sweep_releasesLockOnFailure() {
        var failing =
                new IdempotencyCleanupScheduler(
                        lock,
                        retention -> {
                            throw new IllegalStateException("db down");
                        },
                        new IdempotencyCleanupProperties(Duration.ofMinutes(1), RETENTION));

        assertThatThrownBy(failing::sweep).isInstanceOf(IllegalStateException.class);

        assertThat(lock.tryAcquire(IdempotencyCleanupScheduler.LOCK_KEY, Duration.ofMinutes(1)))
                .isPresent();
    }

    @Test
    @DisplayName("the lock key and lease are the ones distributed-coordination.md §2 fixes")
    void lockKeyAndLease() {
        assertThat(IdempotencyCleanupScheduler.LOCK_KEY)
                .isEqualTo("unihub:lock:job:idempotency-cleanup");
        assertThat(IdempotencyCleanupScheduler.LEASE).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("interval and retention must be positive")
    void properties_rejectNonPositive() {
        assertThatThrownBy(() -> new IdempotencyCleanupProperties(Duration.ZERO, RETENTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new IdempotencyCleanupProperties(
                                        Duration.ofMinutes(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyCleanupProperties(null, RETENTION))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
