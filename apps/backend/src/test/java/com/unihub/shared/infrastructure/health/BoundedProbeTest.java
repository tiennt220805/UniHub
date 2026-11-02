package com.unihub.shared.infrastructure.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class BoundedProbeTest {

    private static final Status DEGRADED = new Status("DEGRADED");

    private BoundedProbe probe;

    @AfterEach
    void stop() {
        probe.shutdown();
    }

    @Test
    @DisplayName("an answer inside the limit is returned as it is")
    void answersWithinTheLimit() {
        probe = new BoundedProbe("t", Duration.ofSeconds(1), DEGRADED);

        assertThat(probe.run(() -> Health.up().withDetail("version", "1").build()).getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("a check that throws reports the failure status and only the exception class")
    void thrownExceptionBecomesTheFailureStatus() {
        probe = new BoundedProbe("t", Duration.ofSeconds(1), DEGRADED);

        var health =
                probe.run(
                        () -> {
                            throw new IllegalStateException("redis://user:secret@host");
                        });

        assertThat(health.getStatus()).isEqualTo(DEGRADED);
        assertThat(health.getDetails()).containsEntry("error", "IllegalStateException");
        assertThat(health.getDetails().toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("a check that hangs is abandoned at the limit, and the next call fails at once")
    void hungCheckIsAbandonedAndNotQueuedBehind() throws Exception {
        probe = new BoundedProbe("t", Duration.ofMillis(100), Status.DOWN);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        // A check that ignores interruption, as a blocking socket read does.
        java.util.function.Supplier<Health> stuck =
                () -> {
                    started.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // keep blocking
                        }
                    }
                    return Health.up().build();
                };

        long begin = System.nanoTime();
        var first = probe.run(stuck);
        var elapsed = Duration.ofNanos(System.nanoTime() - begin);
        assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(first.getStatus()).isEqualTo(Status.DOWN);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));

        long beginSecond = System.nanoTime();
        var second = probe.run(() -> Health.up().build());
        assertThat(second.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) second.getDetails().get("error")).contains("still running");
        assertThat(Duration.ofNanos(System.nanoTime() - beginSecond))
                .isLessThan(Duration.ofMillis(50));

        release.countDown();
    }
}
