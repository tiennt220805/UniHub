package com.unihub.shared.infrastructure.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** The P1 signal (D77): 1 and 0 from a background probe, NaN until the first answer. */
class DependencyUpGaugesTest {
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CountDownLatch never = new CountDownLatch(1);
    private DependencyUpGauges gauges;

    @AfterEach
    void stop() {
        never.countDown();
        if (gauges != null) {
            gauges.shutdown();
        }
    }

    private DependencyUpGauges.Dependency dependency(String name, HealthIndicator check) {
        return new DependencyUpGauges.Dependency(
                name,
                check,
                new BoundedProbe(
                        "test-" + name, Duration.ofMillis(100), DependencyHealthConfig.DEGRADED));
    }

    private double up(String dependency) {
        return registry.get(DependencyUpGauges.NAME).tag("dependency", dependency).gauge().value();
    }

    @Test
    @DisplayName("an answering dependency is 1, one that throws is 0, and both series exist")
    void upAndDown() {
        gauges =
                new DependencyUpGauges(
                        registry,
                        Duration.ofSeconds(30),
                        true,
                        dependency("redis", () -> Health.up().build()),
                        dependency(
                                "rabbitmq",
                                () -> {
                                    throw new IllegalStateException("refused");
                                }));

        await().atMost(PATIENCE)
                .untilAsserted(
                        () -> {
                            assertThat(up("redis")).isEqualTo(1.0);
                            assertThat(up("rabbitmq")).isZero();
                        });
    }

    @Test
    @DisplayName("a dependency that hangs is 0 inside the limit, and the other is still probed")
    void hungDependencyIsDownAndDoesNotStopTheOthers() {
        gauges =
                new DependencyUpGauges(
                        registry,
                        Duration.ofSeconds(30),
                        true,
                        dependency(
                                "redis",
                                () -> {
                                    try {
                                        never.await();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return Health.up().build();
                                }),
                        dependency("rabbitmq", () -> Health.up().build()));

        await().atMost(PATIENCE)
                .untilAsserted(
                        () -> {
                            assertThat(up("redis")).isZero();
                            assertThat(up("rabbitmq")).isEqualTo(1.0);
                        });
    }

    @Test
    @DisplayName(
            "the gauge read never waits on a dependency: it is NaN while the first probe hangs")
    void readNeverWaits() {
        gauges =
                new DependencyUpGauges(
                        registry,
                        Duration.ofSeconds(30),
                        true,
                        dependency(
                                "redis",
                                () -> {
                                    try {
                                        never.await();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return Health.up().build();
                                }),
                        dependency("rabbitmq", () -> Health.up().build()));

        var started = System.nanoTime();
        var read = up("redis");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(50));
        assertThat(read).satisfiesAnyOf(v -> assertThat(v).isNaN(), v -> assertThat(v).isZero());
    }

    @Test
    @DisplayName("switched off, both series exist at NaN and nothing is ever probed")
    void switchedOffRegistersButNeverProbes() throws InterruptedException {
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        HealthIndicator counting =
                () -> {
                    asked.incrementAndGet();
                    return Health.up().build();
                };
        gauges =
                new DependencyUpGauges(
                        registry,
                        Duration.ofMillis(10),
                        false,
                        dependency("redis", counting),
                        dependency("rabbitmq", counting));

        Thread.sleep(200);

        assertThat(asked).hasValue(0);
        assertThat(up("redis")).isNaN();
        assertThat(up("rabbitmq")).isNaN();
    }
}
