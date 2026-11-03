package com.unihub.shared.infrastructure.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.application.port.in.RelayPendingEventsUseCase;
import com.unihub.shared.infrastructure.out.lock.InProcessJobLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventRelaySchedulerTest {

    private final InProcessJobLock lock = new InProcessJobLock();
    private final List<Integer> batches = new ArrayList<>();
    private final RelayPendingEventsUseCase relay =
            batchSize -> {
                batches.add(batchSize);
                return 0;
            };
    private final OutboxRelayProperties properties =
            new OutboxRelayProperties(Duration.ofSeconds(1), 200);

    @Test
    @DisplayName("runs one cycle with the configured batch size under the job lock")
    void run_runsUnderTheLock() {
        new EventRelayScheduler(lock, relay, properties, JobCycle.untraced()).run();

        assertThat(batches).containsExactly(200);
        assertThat(lock.tryAcquire(EventRelayScheduler.LOCK_KEY, Duration.ofSeconds(30)))
                .as("lock released after the cycle")
                .isPresent();
    }

    @Test
    @DisplayName("skips the cycle when another holder has the lock")
    void run_skippedWhenLockHeld() {
        try (var held =
                lock.tryAcquire(EventRelayScheduler.LOCK_KEY, Duration.ofSeconds(30))
                        .orElseThrow()) {
            new EventRelayScheduler(lock, relay, properties, JobCycle.untraced()).run();
        }

        assertThat(batches).isEmpty();
    }

    @Test
    @DisplayName("a failing cycle releases the lock and does not throw, so the schedule survives")
    void run_failureIsContained() {
        var failing =
                new EventRelayScheduler(
                        lock,
                        batchSize -> {
                            throw new IllegalStateException("db down");
                        },
                        properties,
                        JobCycle.untraced());

        assertThatCode(failing::run).doesNotThrowAnyException();

        assertThat(lock.tryAcquire(EventRelayScheduler.LOCK_KEY, Duration.ofSeconds(30)))
                .isPresent();
    }

    @Test
    @DisplayName("the lock key and lease are the ones messaging.md §5 fixes")
    void lockKeyAndLease() {
        assertThat(EventRelayScheduler.LOCK_KEY).isEqualTo("unihub:lock:job:outbox-relay");
        assertThat(EventRelayScheduler.LEASE).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("interval and batch size must be positive")
    void properties_rejectNonPositive() {
        assertThatThrownBy(() -> new OutboxRelayProperties(Duration.ZERO, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelayProperties(Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRelayProperties(null, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
