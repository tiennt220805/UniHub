package com.unihub.shared.infrastructure.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.MultiInstanceHarness;
import com.unihub.shared.infrastructure.MultiInstanceHarness.Locking;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;

/**
 * M-35, INV-09 (checklist D22, C1, C10): two application instances run a real scheduled job — the
 * idempotency-key cleanup — and the lock never lets the two runs overlap in time.
 *
 * <p>It asserts "never two at once", not "once per cycle": four of the five jobs use {@code
 * fixedDelay} and release the lock in {@code finally}, so a second instance may legitimately run
 * right after the first has finished (checklist C10). It does not count deleted rows either: every
 * job is idempotent under a duplicate run, so a double run leaves no trace in the data; the
 * measurement is on the lock ({@link MultiInstanceHarness}).
 *
 * <p>The control runs the same scenario with an in-process lock in each context. It must show the
 * overlap; if it does not, the harness is not separating the instances and the Redisson result
 * above would prove nothing. In the package of the scheduler because the scheduler is
 * package-private.
 */
class JobSingleInstanceIT extends IntegrationTestSupport {

    /** A real body takes milliseconds; this is added to the hold so that contention is certain. */
    private static final Duration HOLD = Duration.ofMillis(300);

    private static final long HEAD_START_MILLIS = 60;
    private static final int ROUNDS = 6;

    @Test
    @Timeout(180)
    @DisplayName("with the Redisson lock, two instances never hold the job lock at the same time")
    void redisson_noTwoRunsOverlap() throws Exception {
        try (var instances = MultiInstanceHarness.start(2, Locking.REDISSON, HOLD)) {
            assertThat(instances.bean(0, RedissonClient.class).getId())
                    .as("each instance has its own Redis client")
                    .isNotEqualTo(instances.bean(1, RedissonClient.class).getId());

            runRounds(instances);

            var log = instances.log();
            assertThat(log.overlaps()).as("holds of the same key in force together").isEmpty();
            assertThat(log.holdsOf(0)).as("instance 0 ran the job").isNotEmpty();
            assertThat(log.holdsOf(1)).as("instance 1 ran the job").isNotEmpty();
            assertThat(log.totalSkips())
                    .as("the other instance found the lock taken and skipped its cycle")
                    .isGreaterThanOrEqualTo(ROUNDS);
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("control: with a lock per instance in memory, the same scenario overlaps")
    void control_inProcessLocks_overlap() throws Exception {
        try (var instances = MultiInstanceHarness.start(2, Locking.IN_PROCESS, HOLD)) {
            runRounds(instances);

            var log = instances.log();
            assertThat(log.overlaps())
                    .as("two instances sharing no lock must run the job together")
                    .isNotEmpty();
            assertThat(log.totalSkips()).as("nobody was ever refused").isZero();
        }
    }

    /**
     * Staggered rounds, in which one instance starts first and the other asks while the first still
     * holds, alternating who is first; then rounds in which both ask at the same instant.
     */
    private static void runRounds(MultiInstanceHarness instances) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            int first = round % 2;
            var head = sweepAsync(instances, first, null);
            Thread.sleep(HEAD_START_MILLIS);
            var tail = sweepAsync(instances, 1 - first, null);
            CompletableFuture.allOf(head, tail).get(30, TimeUnit.SECONDS);
        }
        for (int round = 0; round < ROUNDS; round++) {
            var barrier = new CyclicBarrier(2);
            var a = sweepAsync(instances, 0, barrier);
            var b = sweepAsync(instances, 1, barrier);
            CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
        }
    }

    private static CompletableFuture<Void> sweepAsync(
            MultiInstanceHarness instances, int instance, CyclicBarrier barrier) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        if (barrier != null) {
                            barrier.await(10, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    instances.bean(instance, IdempotencyCleanupScheduler.class).sweep();
                });
    }
}
