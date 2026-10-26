package com.unihub.shared.infrastructure.config;

import java.time.Duration;
import org.redisson.api.RedissonClient;

/**
 * Builds the Redisson client exactly as production does ({@link RedisConfig}: lazy initialization,
 * 0.5 s timeouts, no retries). A fault-injection test that used a hand-rolled client would prove
 * nothing about how the real one behaves against a stopped or hung Redis (checklist D20).
 */
public final class RedisTestClients {

    private RedisTestClients() {}

    public static RedissonClient production(String address) {
        return new RedisConfig()
                .redissonClient(
                        new RedisProperties(
                                address, null, Duration.ofMillis(500), Duration.ofMillis(500)));
    }
}
