package com.unihub.registration.infrastructure.out.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.config.RedisTestClients;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * The Redisson seat lock against a Redis that is broken or whose lease runs out: INV-08 at the
 * adapter (checklist D20, closes Q2; the system-level proof is {@code RedisOutageIT}, M-34).
 *
 * <p>Runs on its OWN Redis container, never the shared singleton: stopping or pausing that one
 * would fail every later class in the run (resilience-testing.md §3). The client is built the way
 * production builds it (0.5 s command timeout, no retries), because a hand-made client would prove
 * nothing about how a hung connection really surfaces. Asserts the outcome — unavailable, in
 * bounded time — not which Redisson exception carried it.
 */
class RedissonSeatLockIT extends IntegrationTestSupport {
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(5);

    /** wait 2 s + command timeout 0.5 s, plus a margin for scheduling and the Docker round trip. */
    private static final Duration BOUND = Duration.ofSeconds(4);

    private GenericContainer<?> redisContainer;
    private RedissonClient redis;
    private RedissonSeatLock lock;
    private final ExecutorService threads = Executors.newCachedThreadPool();

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
        lock = new RedissonSeatLock(redis);
    }

    @AfterEach
    void stopRedis() {
        threads.shutdownNow();
        try {
            redis.shutdown(0, 1, TimeUnit.SECONDS);
        } finally {
            redisContainer.stop();
        }
    }

    @Test
    @DisplayName("a holder whose lease ran out releases nothing it no longer owns")
    void leaseExpired_closeNeverReleasesTheNewHolder() throws Exception {
        var workshop = WorkshopId.newId();
        var shortLease = Duration.ofMillis(200);
        var firstHolder = lock.tryAcquire(workshop, WAIT, shortLease);
        Thread.sleep(shortLease.toMillis() + 300);

        // the lease expired, so another request legitimately takes the lock (on its own thread)
        var secondTaken = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        var second =
                CompletableFuture.runAsync(
                        () -> {
                            try (var held = lock.tryAcquire(workshop, WAIT, LEASE)) {
                                secondTaken.countDown();
                                await(secondRelease);
                            }
                        },
                        threads);
        assertThat(secondTaken.await(5, TimeUnit.SECONDS)).isTrue();

        // the first holder finishes late: its close must not throw and must not free the second's
        // lock
        assertThatCode(firstHolder::close).doesNotThrowAnyException();
        assertThat(acquirableElsewhere(workshop)).isFalse();

        secondRelease.countDown();
        second.get(5, TimeUnit.SECONDS);
        assertThat(acquirableElsewhere(workshop)).isTrue();
    }

    @Test
    @DisplayName("Redis stopped (connection refused): no lock, in bounded time")
    void redisStopped_isUnavailable() {
        var workshop = WorkshopId.newId();
        lock.tryAcquire(workshop, WAIT, LEASE).close(); // warm the pool: the first command connects
        dockerStop();

        var started = System.nanoTime();
        assertThatThrownBy(() -> lock.tryAcquire(workshop, WAIT, LEASE))
                .isInstanceOf(SeatLockUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND);
    }

    @Test
    @Timeout(60)
    @DisplayName(
            "Redis paused (connection hangs): no lock, in bounded time; recovers within the lease")
    void redisPaused_isUnavailable_thenRecovers() {
        var workshop = WorkshopId.newId();
        lock.tryAcquire(workshop, WAIT, LEASE)
                .close(); // warm the pool: the hang is on a live socket
        dockerPause();
        try {
            var started = System.nanoTime();
            assertThatThrownBy(() -> lock.tryAcquire(workshop, WAIT, LEASE))
                    .isInstanceOf(SeatLockUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND);
        } finally {
            dockerUnpause();
        }

        // No restart of anything: the same client works again once Redis answers. Not instantly:
        // the
        // acquire that timed out while Redis was paused may still be executed on unpause, and then
        // holds the lock for a request that is long gone, until its lease runs out. That orphan is
        // bounded by the lease, which is the reason the lease is fixed and short.
        assertThat(acquirableWithin(workshop, LEASE.plusSeconds(2))).isTrue();
    }

    @Test
    @Timeout(60)
    @DisplayName("closing a handle while Redis is paused does not throw")
    void closeWhileRedisPaused_doesNotThrow() {
        var held = lock.tryAcquire(WorkshopId.newId(), WAIT, LEASE);
        dockerPause();
        try {
            assertThatCode(held::close).doesNotThrowAnyException();
        } finally {
            dockerUnpause();
        }
    }

    private boolean acquirableWithin(WorkshopId workshop, Duration limit) {
        var deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (acquirableElsewhere(workshop)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean acquirableElsewhere(WorkshopId workshop) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            try (var held =
                                    lock.tryAcquire(workshop, Duration.ofMillis(50), LEASE)) {
                                return true;
                            } catch (SeatLockUnavailableException busy) {
                                return false;
                            }
                        },
                        threads)
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
    }

    private void dockerStop() {
        DockerClientFactory.instance()
                .client()
                .stopContainerCmd(redisContainer.getContainerId())
                .exec();
    }

    private void dockerPause() {
        DockerClientFactory.instance()
                .client()
                .pauseContainerCmd(redisContainer.getContainerId())
                .exec();
    }

    private void dockerUnpause() {
        DockerClientFactory.instance()
                .client()
                .unpauseContainerCmd(redisContainer.getContainerId())
                .exec();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
