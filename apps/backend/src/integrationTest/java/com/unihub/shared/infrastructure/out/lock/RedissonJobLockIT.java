package com.unihub.shared.infrastructure.out.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.config.RedisTestClients;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * The Redisson job lock against a broken Redis and an expired lease (AC20.3, INV-09): the cycle is
 * skipped, never run uncoordinated, and the lease protects a late holder's successor. Own Redis
 * container, production client config — see {@code RedissonSeatLockIT} for why.
 */
class RedissonJobLockIT extends IntegrationTestSupport {
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final Duration BOUND = Duration.ofSeconds(3);

    private GenericContainer<?> redisContainer;
    private RedissonClient redis;
    private RedissonJobLock lock;
    private String key;

    @BeforeEach
    void startRedis() {
        redisContainer = new GenericContainer<>("redis:7").withExposedPorts(6379);
        redisContainer.start();
        redis =
                RedisTestClients.production(
                        "redis://"
                                + redisContainer.getHost()
                                + ":"
                                + redisContainer.getMappedPort(6379));
        lock = new RedissonJobLock(redis);
        key = "unihub:lock:job:it-" + UUID.randomUUID();
    }

    @AfterEach
    void stopRedis() {
        try {
            redis.shutdown(0, 1, TimeUnit.SECONDS);
        } finally {
            redisContainer.stop();
        }
    }

    @Test
    @DisplayName("a holder whose lease ran out releases nothing it no longer owns")
    void leaseExpired_closeNeverReleasesTheNewHolder() throws Exception {
        var first = lock.tryAcquire(key, Duration.ofMillis(200)).orElseThrow();
        Thread.sleep(500);
        // the lease expired: another thread legitimately takes the key
        var second = CompletableFuture.supplyAsync(() -> lock.tryAcquire(key, LEASE).orElseThrow());
        var secondHandle = second.get(5, TimeUnit.SECONDS);

        assertThatCode(first::close).doesNotThrowAnyException();

        // still held by the second: a third caller is refused
        assertThat(CompletableFuture.supplyAsync(() -> lock.tryAcquire(key, LEASE)).join())
                .isEmpty();
        CompletableFuture.runAsync(secondHandle::close).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Redis stopped: the cycle is skipped in bounded time")
    void redisStopped_skipsTheCycle() {
        lock.tryAcquire(key, LEASE).orElseThrow().close(); // warm the pool
        DockerClientFactory.instance()
                .client()
                .stopContainerCmd(redisContainer.getContainerId())
                .exec();

        var started = System.nanoTime();
        var held = lock.tryAcquire(key, LEASE);

        assertThat(held).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND);
    }

    @Test
    @Timeout(60)
    @DisplayName("Redis paused (connection hangs): the cycle is skipped in bounded time")
    void redisPaused_skipsTheCycle() {
        lock.tryAcquire(key, LEASE).orElseThrow().close(); // warm the pool
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            var started = System.nanoTime();
            var held = lock.tryAcquire(key, LEASE);

            assertThat(held).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND);
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }
}
