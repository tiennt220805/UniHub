package com.unihub.registration.infrastructure.out.lock;

import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.config.RedisTestClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.redisson.api.RedissonClient;

/**
 * The shared {@link SeatLockContractTest} against a real Redis (INV-06, INV-08; M-28, M-32). The
 * contract is a nested class so that this one extends {@link IntegrationTestSupport}, as every
 * {@code *IT} must (M-16).
 */
class RedissonSeatLockContractIT extends IntegrationTestSupport {

    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        redis = RedisTestClients.production(redisAddress());
    }

    @AfterAll
    static void disconnect() {
        redis.shutdown();
    }

    @Nested
    class Contract extends SeatLockContractTest {
        @Override
        protected SeatLock newLock() {
            return new RedissonSeatLock(redis);
        }
    }
}
