package com.unihub.shared.infrastructure.out.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.application.port.out.RateLimitDecision;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.config.RedisTestClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * The Redisson rate limiter against a real Redis (INV-17, distributed-coordination.md §3): the
 * budget is one count for every client, an expired key starts clean, and a Redis that is stopped or
 * paused is let through quickly and counted (§4). Own Redis container and production client config,
 * like {@code RedissonJobLockIT}.
 */
class RedissonRateLimiterIT extends IntegrationTestSupport {

    private static final Duration COOLDOWN = Duration.ofSeconds(2);

    private GenericContainer<?> redisContainer;
    private RedissonClient redis;
    private SimpleMeterRegistry meters;
    private RedissonRateLimiter limiter;
    private String key;

    @BeforeEach
    void startRedis() {
        redisContainer = new GenericContainer<>("redis:7").withExposedPorts(6379);
        redisContainer.start();
        redis = client();
        meters = new SimpleMeterRegistry();
        limiter = new RedissonRateLimiter(redis, Clock.systemUTC(), COOLDOWN, meters);
        key = "unihub:ratelimit:it-" + UUID.randomUUID();
    }

    private RedissonClient client() {
        return RedisTestClients.production(
                "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
    }

    @AfterEach
    void stopRedis() {
        try {
            redis.shutdown(0, 1, TimeUnit.SECONDS);
        } finally {
            redisContainer.stop();
        }
    }

    private double bypassed(String reason) {
        return meters.get(RedissonRateLimiter.BYPASS_COUNTER)
                .tag("reason", reason)
                .counter()
                .count();
    }

    @Test
    @DisplayName("the first `limit` calls are allowed and the next is rejected with the window")
    void budget_isLimitThenRejected() {
        var window = Duration.ofSeconds(10);
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryAcquire(key, 5, window))
                    .as("call %d", i)
                    .isInstanceOf(RateLimitDecision.Allowed.class);
        }

        assertThat(limiter.tryAcquire(key, 5, window))
                .isEqualTo(new RateLimitDecision.Rejected(window));
        // a rejected call is not a bypass
        assertThat(bypassed("error") + bypassed("timeout")).isZero();
    }

    @Test
    @DisplayName("two clients share one budget: OVERALL, not per client (INV-17)")
    void twoClients_shareOneBudget() {
        var other = client();
        try {
            var second =
                    new RedissonRateLimiter(
                            other, Clock.systemUTC(), COOLDOWN, new SimpleMeterRegistry());
            var window = Duration.ofSeconds(30);
            int allowed = 0;
            for (int i = 0; i < 10; i++) {
                var decision = (i % 2 == 0 ? limiter : second).tryAcquire(key, 5, window);
                if (decision instanceof RateLimitDecision.Allowed) {
                    allowed++;
                }
            }

            assertThat(allowed).isEqualTo(5);
        } finally {
            other.shutdown(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("permits come back after the window")
    void permits_returnAfterTheWindow() throws Exception {
        var window = Duration.ofSeconds(1);
        assertThat(limiter.tryAcquire(key, 2, window))
                .isInstanceOf(RateLimitDecision.Allowed.class);
        assertThat(limiter.tryAcquire(key, 2, window))
                .isInstanceOf(RateLimitDecision.Allowed.class);
        assertThat(limiter.tryAcquire(key, 2, window))
                .isInstanceOf(RateLimitDecision.Rejected.class);

        Thread.sleep(1_200);

        assertThat(limiter.tryAcquire(key, 2, window))
                .isInstanceOf(RateLimitDecision.Allowed.class);
    }

    @Test
    @DisplayName(
            "what Redisson throws for a key that was never set, and an expired key starts clean")
    @Timeout(60)
    void expiredKey_isNotInitialized_thenStartsClean() throws Exception {
        // 1. the exact failure the adapter keys its re-initialisation on
        assertThatThrownBy(() -> redis.getRateLimiter(key).tryAcquire())
                .isInstanceOf(RedisException.class)
                .hasMessageContaining("RateLimiter is not initialized");

        // 2. a key used and then idle for longer than its keep-alive (2 x window) is gone ...
        var window = Duration.ofMillis(500);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(key, 3, window);
        }
        assertThat(limiter.tryAcquire(key, 3, window))
                .isInstanceOf(RateLimitDecision.Rejected.class);
        Thread.sleep(2_500);
        assertThat(redis.getRateLimiter(key).isExists()).as("key expired").isFalse();

        // 3. ... and the next call makes a fresh budget instead of failing, and keeps the limit
        var again = new ArrayList<RateLimitDecision>();
        for (int i = 0; i < 4; i++) {
            again.add(limiter.tryAcquire(key, 3, window));
        }
        assertThat(again.subList(0, 3)).allMatch(d -> d instanceof RateLimitDecision.Allowed);
        assertThat(again.get(3)).isInstanceOf(RateLimitDecision.Rejected.class);
        assertThat(bypassed("error") + bypassed("timeout")).isZero();
    }

    @Test
    @DisplayName("Redis stopped: let through at once, counted, and left alone for the cooldown")
    @Timeout(60)
    void redisStopped_unavailable_counted_thenCooldown() throws Exception {
        var window = Duration.ofSeconds(10);
        assertThat(limiter.tryAcquire(key, 5, window))
                .isInstanceOf(RateLimitDecision.Allowed.class);

        DockerClientFactory.instance()
                .client()
                .stopContainerCmd(redisContainer.getContainerId())
                .exec();

        long start = System.nanoTime();
        var first = limiter.tryAcquire(key, 5, window);
        var firstTook = Duration.ofNanos(System.nanoTime() - start);

        assertThat(first).isInstanceOf(RateLimitDecision.Unavailable.class);
        assertThat(firstTook).isLessThan(Duration.ofSeconds(2));
        assertThat(bypassed("error") + bypassed("timeout")).isEqualTo(1);

        // within the cooldown Redis is not asked: no wait at all, still counted
        start = System.nanoTime();
        var second = limiter.tryAcquire(key, 5, window);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        assertThat(second).isInstanceOf(RateLimitDecision.Unavailable.class);
        assertThat(bypassed("error") + bypassed("timeout")).isEqualTo(2);
    }

    @Test
    @DisplayName("Redis paused: let through within the command timeout, counted as a timeout")
    @Timeout(60)
    void redisPaused_timesOut_counted() {
        var window = Duration.ofSeconds(10);
        assertThat(limiter.tryAcquire(key, 5, window))
                .isInstanceOf(RateLimitDecision.Allowed.class);

        DockerClientFactory.instance()
                .client()
                .pauseContainerCmd(redisContainer.getContainerId())
                .exec();
        try {
            long start = System.nanoTime();
            var decision = limiter.tryAcquire(key, 5, window);
            var took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(decision).isEqualTo(new RateLimitDecision.Unavailable("timeout"));
            // a hung Redis costs one command timeout (0.5 s), not two: no retry of an outage
            assertThat(took).isLessThan(Duration.ofSeconds(2));
            assertThat(bypassed("timeout")).isEqualTo(1);
        } finally {
            DockerClientFactory.instance()
                    .client()
                    .unpauseContainerCmd(redisContainer.getContainerId())
                    .exec();
        }
    }
}
