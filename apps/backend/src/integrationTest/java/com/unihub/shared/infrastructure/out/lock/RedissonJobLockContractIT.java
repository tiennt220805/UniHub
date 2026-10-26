package com.unihub.shared.infrastructure.out.lock;

import com.unihub.shared.application.port.out.JobLock;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.config.RedisTestClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.redisson.api.RedissonClient;

/**
 * The shared {@link JobLockContractTest} against a real Redis (INV-09; M-09, M-35). The contract is
 * a nested class so that this one extends {@link IntegrationTestSupport}, as every {@code *IT} must
 * (M-16).
 */
class RedissonJobLockContractIT extends IntegrationTestSupport {

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
    class Contract extends JobLockContractTest {
        @Override
        protected JobLock newLock() {
            return new RedissonJobLock(redis);
        }
    }
}
