package com.unihub.shared.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one Redis client (single-server Redisson, distributed-coordination.md §1), created only when
 * {@code unihub.redis.address} is set. Connection pool sizes are Redisson's defaults — no load test
 * yet justifies other numbers. Lazy initialization: nothing connects until the first command, so
 * the application starts, and stays up, with Redis unreachable.
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
@ConditionalOnExpression("!'${unihub.redis.address:}'.isEmpty()")
class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(RedisProperties properties) {
        var config = new Config();
        config.setLazyInitialization(true);
        var server =
                config.useSingleServer()
                        .setAddress(properties.address())
                        .setConnectTimeout((int) properties.connectTimeout().toMillis())
                        .setTimeout((int) properties.commandTimeout().toMillis())
                        .setRetryAttempts(0);
        if (properties.password() != null && !properties.password().isBlank()) {
            server.setPassword(properties.password());
        }
        return Redisson.create(config);
    }
}
