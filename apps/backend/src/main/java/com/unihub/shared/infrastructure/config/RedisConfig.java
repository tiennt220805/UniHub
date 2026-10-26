package com.unihub.shared.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one Redis client (single-server Redisson, distributed-coordination.md §1). {@code
 * unihub.redis.address} is required (checklist D21): the seat lock and the job lock have no
 * in-process fallback, which would run uncoordinated on more than one pod without anyone noticing.
 * Connection pool sizes are Redisson's defaults — no load test yet justifies other numbers. Lazy
 * initialization: nothing connects until the first command, so an address that is set but
 * unreachable still starts the application, which then refuses registrations with 503.
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
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
