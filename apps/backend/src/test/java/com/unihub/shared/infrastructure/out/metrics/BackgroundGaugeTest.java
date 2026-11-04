package com.unihub.shared.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackgroundGaugeTest {
    private final AtomicLong nanos = new AtomicLong();

    private BackgroundGauge gauge(DoubleSupplier source) {
        return new BackgroundGauge("g", source, Duration.ofSeconds(60), nanos::get);
    }

    @Test
    @DisplayName("the value is NaN until the first read, then the value read")
    void nanUntilFirstRead() {
        var gauge = gauge(() -> 42.0);
        assertThat(gauge.value()).isNaN();

        gauge.refresh();

        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    @DisplayName("a read that throws turns the value into NaN instead of keeping a stale number")
    void failedReadIsUnknown() {
        var fail = new boolean[] {false};
        var gauge =
                gauge(
                        () -> {
                            if (fail[0]) {
                                throw new IllegalStateException("database down");
                            }
                            return 7.0;
                        });
        gauge.refresh();
        assertThat(gauge.value()).isEqualTo(7.0);

        fail[0] = true;
        gauge.refresh();

        assertThat(gauge.value()).isNaN();
    }

    @Test
    @DisplayName("a value older than staleAfter is NaN: a read that hangs never reports back")
    void staleValueIsUnknown() {
        var gauge = gauge(() -> 5.0);
        gauge.refresh();
        nanos.addAndGet(Duration.ofSeconds(60).toNanos());
        assertThat(gauge.value()).isEqualTo(5.0);

        nanos.addAndGet(1);

        assertThat(gauge.value()).isNaN();
    }

    @Test
    @DisplayName("a source that blocks forever never blocks the gauge read (D77)")
    void scrapeNeverWaitsOnTheSource() {
        var never = new CountDownLatch(1);
        var registry = new SimpleMeterRegistry();
        var gauges = new BackgroundGauges(true);
        try {
            gauges.register(
                    registry,
                    "unihub.test.blocked",
                    "d",
                    "seconds",
                    () -> {
                        try {
                            never.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return 1.0;
                    });
            var started = System.nanoTime();

            var read = registry.get("unihub.test.blocked").gauge().value();

            assertThat(read).isNaN();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(500));
        } finally {
            never.countDown();
            gauges.close();
        }
    }

    @Test
    @DisplayName("a registered gauge is read in the background and follows the source")
    void registeredGaugeFollowsTheSource() {
        var registry = new SimpleMeterRegistry();
        var gauges = new BackgroundGauges(true);
        try {
            gauges.register(
                    registry,
                    "unihub.test.value",
                    "d",
                    "seconds",
                    () -> 3.0,
                    Duration.ofMillis(20));

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () ->
                                    assertThat(registry.get("unihub.test.value").gauge().value())
                                            .isEqualTo(3.0));
        } finally {
            gauges.close();
        }
    }

    @Test
    @DisplayName("switched off, the series exists at NaN and the source is never read")
    void switchedOffNeverReads() throws InterruptedException {
        var registry = new SimpleMeterRegistry();
        var reads = new AtomicInteger();
        var gauges = new BackgroundGauges(false);
        try {
            gauges.register(
                    registry,
                    "unihub.test.off",
                    "d",
                    "seconds",
                    () -> {
                        reads.incrementAndGet();
                        return 1.0;
                    },
                    Duration.ofMillis(10));

            Thread.sleep(150);

            assertThat(reads).hasValue(0);
            assertThat(registry.get("unihub.test.off").gauge().value()).isNaN();
        } finally {
            gauges.close();
        }
    }

    @Test
    @DisplayName("two threads serve every gauge: a hung read never pins more than two connections")
    void twoHungReadsLeaveTheThirdUnread() throws InterruptedException {
        var never = new CountDownLatch(1);
        var registry = new SimpleMeterRegistry();
        var gauges = new BackgroundGauges(true);
        try {
            for (var name : new String[] {"unihub.test.a", "unihub.test.b"}) {
                gauges.register(
                        registry,
                        name,
                        "d",
                        "seconds",
                        () -> {
                            try {
                                never.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return 1.0;
                        });
            }
            var third = new AtomicInteger();
            gauges.register(
                    registry,
                    "unihub.test.c",
                    "d",
                    "seconds",
                    () -> {
                        third.incrementAndGet();
                        return 1.0;
                    });

            Thread.sleep(200);

            assertThat(third).as("both threads are held by hung reads").hasValue(0);
        } finally {
            never.countDown();
            gauges.close();
        }
    }
}
