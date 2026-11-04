package com.unihub.shared.infrastructure.health;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three health indicators behind the readiness and dependencies groups (observability.md §5,
 * Phase 6 checklist D64). Boot's own {@code db}, {@code rabbit} and {@code mail} indicators are off
 * in application.yml and these replace them:
 *
 * <ul>
 *   <li>{@code db} asks the PostgreSQL Master only. Boot's would also ask the Replica and the
 *       routing proxy. Readiness is the one probe that takes a pod out of rotation, so a Master
 *       that does not answer inside the limit is {@code DOWN}.
 *   <li>{@code redis} and {@code rabbit} report {@code DEGRADED}, never {@code DOWN}: their outage
 *       is a designed degradation (registrations refused with 503, audit and outbox waiting), not a
 *       reason to remove a pod from rotation. Both answer inside a hard limit of one second.
 * </ul>
 */
@Configuration
class DependencyHealthConfig {

    static final Status DEGRADED = new Status("DEGRADED");
    static final Duration DEPENDENCY_LIMIT = Duration.ofSeconds(1);
    private static final Duration DATABASE_LIMIT = Duration.ofSeconds(2);

    @Bean(name = "dbHealthIndicator", destroyMethod = "shutdown")
    BoundedHealthIndicator dbHealthIndicator(@Qualifier("masterDataSource") DataSource master) {
        var delegate = new DataSourceHealthIndicator(master, "SELECT 1");
        return new BoundedHealthIndicator(
                new BoundedProbe("db", DATABASE_LIMIT, Status.DOWN), delegate, Status.DOWN);
    }

    @Bean(name = "redisHealthIndicator", destroyMethod = "shutdown")
    BoundedHealthIndicator redisHealthIndicator(RedissonClient redisson) {
        return new BoundedHealthIndicator(
                new BoundedProbe("redis", DEPENDENCY_LIMIT, DEGRADED),
                redisPing(redisson),
                DEGRADED);
    }

    /** The Redis check itself, also run by the {@link DependencyUpGauges} background probe. */
    static HealthIndicator redisPing(RedissonClient redisson) {
        return () ->
                redisson.getNodesGroup().pingAll(DEPENDENCY_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
                        ? Health.up().build()
                        : Health.status(DEGRADED).withDetail("error", "ping refused").build();
    }

    @Bean(name = "rabbitHealthIndicator", destroyMethod = "shutdown")
    BoundedHealthIndicator rabbitHealthIndicator(RabbitTemplate template) {
        return new BoundedHealthIndicator(
                new BoundedProbe("rabbit", DEPENDENCY_LIMIT, DEGRADED),
                rabbitCheck(template),
                DEGRADED);
    }

    /**
     * The broker check itself, also run by the {@link DependencyUpGauges} background probe. A real
     * round trip on a channel ({@code basicQos} waits for the broker's reply). Boot's own {@code
     * RabbitHealthIndicator} reads the server properties kept from the handshake, which a cached
     * connection answers without asking the broker: a broker that is paused, with its sockets still
     * open, would read as up.
     */
    static HealthIndicator rabbitCheck(RabbitTemplate template) {
        return () ->
                template.execute(
                        channel -> {
                            channel.basicQos(0);
                            return Health.up().build();
                        });
    }
}
