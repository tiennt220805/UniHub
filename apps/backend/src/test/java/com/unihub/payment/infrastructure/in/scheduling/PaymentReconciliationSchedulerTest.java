package com.unihub.payment.infrastructure.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.infrastructure.out.lock.InProcessJobLock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentReconciliationSchedulerTest {

    private final InProcessJobLock lock = new InProcessJobLock();
    private final AtomicInteger sweeps = new AtomicInteger();
    private final PaymentReconciliationScheduler scheduler =
            new PaymentReconciliationScheduler(lock, sweeps::incrementAndGet);

    @Test
    @DisplayName("runs the sweep under the job lock and releases it afterwards")
    void sweep_runsUnderTheLock() {
        scheduler.sweep();

        assertThat(sweeps).hasValue(1);
        assertThat(lock.tryAcquire(PaymentReconciliationScheduler.LOCK_KEY, Duration.ofMinutes(5)))
                .as("lock released after the sweep")
                .isPresent();
    }

    @Test
    @DisplayName("skips the cycle when another holder has the lock")
    void sweep_skippedWhenLockHeld() {
        try (var held =
                lock.tryAcquire(PaymentReconciliationScheduler.LOCK_KEY, Duration.ofMinutes(5))
                        .orElseThrow()) {
            scheduler.sweep();
        }

        assertThat(sweeps).hasValue(0);
    }

    @Test
    @DisplayName("releases the lock even when the sweep fails")
    void sweep_releasesLockOnFailure() {
        var failing =
                new PaymentReconciliationScheduler(
                        lock,
                        () -> {
                            throw new IllegalStateException("db down");
                        });

        assertThatThrownBy(failing::sweep).isInstanceOf(IllegalStateException.class);

        assertThat(lock.tryAcquire(PaymentReconciliationScheduler.LOCK_KEY, Duration.ofMinutes(5)))
                .isPresent();
    }

    @Test
    @DisplayName("the lock key and lease are the ones distributed-coordination.md §2 fixes")
    void lockKeyAndLease() {
        assertThat(PaymentReconciliationScheduler.LOCK_KEY)
                .isEqualTo("unihub:lock:job:payment-reconciliation");
        assertThat(PaymentReconciliationScheduler.LEASE).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("the interval must be positive")
    void interval_mustBePositive() {
        assertThatThrownBy(() -> new PaymentReconciliationProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentReconciliationProperties(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
