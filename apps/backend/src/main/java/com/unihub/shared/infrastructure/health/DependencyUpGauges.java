package com.unihub.shared.infrastructure.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * {@code unihub_dependency_up{dependency="redis"|"rabbitmq"}}, 1 or 0: the P1 signal for "Redis or
 * RabbitMQ is down" (observability.md §4, §6; test-plan.md §1 P1; Phase 6 checklist D77).
 *
 * <p>A <b>background</b> probe, not a health call made while Prometheus scrapes: a paused
 * dependency would hold {@code /actuator/prometheus} until the client's own timeout, Prometheus
 * would mark the whole target {@code up = 0}, and every metric would vanish exactly when the alert
 * is needed. Here each dependency is asked on a schedule, behind a hard one-second limit, on a
 * thread of its own (never the application's {@code @Scheduled} pool, which the outbox relay
 * shares); the gauge only returns the last answer. The probes are this class's own {@link
 * BoundedProbe}s, not the health indicators', so a manual {@code /health/dependencies} call can
 * never make a tick read "still running" as down.
 *
 * <p>Both series exist from startup and read NaN until the first answer, so they are never a false
 * 0 and never absent. {@code unihub.observability.background-refresh=false} (integration tests,
 * which keep one idle context per configuration for the whole run) registers them and never probes.
 */
@Component
class DependencyUpGauges {
    private static final Logger LOG = LoggerFactory.getLogger(DependencyUpGauges.class);

    static final String NAME = "unihub.dependency.up";

    private final Dependency redis;
    private final Dependency rabbit;
    private final ScheduledExecutorService executor;

    @Autowired
    DependencyUpGauges(
            RedissonClient redisson,
            RabbitTemplate template,
            MeterRegistry registry,
            @Value("${unihub.observability.dependency-probe-interval:PT5S}") Duration interval,
            @Value("${unihub.observability.background-refresh:true}") boolean enabled) {
        this(
                registry,
                interval,
                enabled,
                new Dependency(
                        "redis",
                        DependencyHealthConfig.redisPing(redisson),
                        new BoundedProbe(
                                "up-redis",
                                DependencyHealthConfig.DEPENDENCY_LIMIT,
                                DependencyHealthConfig.DEGRADED)),
                new Dependency(
                        "rabbitmq",
                        DependencyHealthConfig.rabbitCheck(template),
                        new BoundedProbe(
                                "up-rabbitmq",
                                DependencyHealthConfig.DEPENDENCY_LIMIT,
                                DependencyHealthConfig.DEGRADED)));
    }

    DependencyUpGauges(
            MeterRegistry registry,
            Duration interval,
            boolean enabled,
            Dependency redis,
            Dependency rabbit) {
        this.redis = redis.registeredOn(registry);
        this.rabbit = rabbit.registeredOn(registry);
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            var thread = new Thread(task, "dependency-up-probe");
                            thread.setDaemon(true);
                            return thread;
                        });
        if (enabled) {
            executor.scheduleWithFixedDelay(
                    this::probe, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** One round: each dependency asked once, each behind its own hard limit. */
    void probe() {
        redis.probe();
        rabbit.probe();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        redis.probe.shutdown();
        rabbit.probe.shutdown();
    }

    /** One dependency: what to ask, how long to wait, and the last answer. */
    static final class Dependency {
        private final String name;
        private final HealthIndicator check;
        private final BoundedProbe probe;
        private final AtomicLong upBits = new AtomicLong(Double.doubleToLongBits(Double.NaN));

        Dependency(String name, HealthIndicator check, BoundedProbe probe) {
            this.name = name;
            this.check = check;
            this.probe = probe;
        }

        Dependency registeredOn(MeterRegistry registry) {
            Gauge.builder(NAME, upBits, bits -> Double.longBitsToDouble(bits.get()))
                    .tag("dependency", name)
                    .description("1 when the dependency answered the last background probe, else 0")
                    .strongReference(true)
                    .register(registry);
            return this;
        }

        void probe() {
            try {
                var health = probe.run(() -> check.health());
                var up = Status.UP.equals(health.getStatus());
                upBits.set(Double.doubleToLongBits(up ? 1.0 : 0.0));
                if (!up) {
                    LOG.debug("{} did not answer the probe: {}", name, health.getDetails());
                }
            } catch (RuntimeException e) {
                upBits.set(Double.doubleToLongBits(0.0));
            }
        }
    }
}
