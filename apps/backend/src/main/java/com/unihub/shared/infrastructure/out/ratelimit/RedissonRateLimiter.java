package com.unihub.shared.infrastructure.out.ratelimit;

import com.unihub.shared.application.port.out.RateLimitDecision;
import com.unihub.shared.application.port.out.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.RedisTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RateLimiter} on Redisson's {@code RRateLimiter} (distributed-coordination.md §3, INV-17).
 *
 * <p><b>{@code OVERALL}, not {@code PER_CLIENT}.</b> {@code PER_CLIENT} counts per {@code
 * RedissonClient}, which is per pod: two pods would each admit the full budget. {@code OVERALL} is
 * one count in Redis for every pod.
 *
 * <p><b>One key per identity, expiring.</b> The rate is set the first time a key is used, with a
 * keep-alive of twice the window, so an identity that goes quiet leaves nothing behind. When the
 * key has expired, {@code tryAcquire}'s script fails with {@code RateLimiter is not initialized};
 * the rate is then set again and the acquire tried once more. No pod-local "already configured"
 * cache: it would turn into errors the moment a key expires.
 *
 * <p><b>Fail open, counted, and not slow.</b> Redis stopped or paused costs one command timeout
 * ({@code unihub.redis.command-timeout}, 0.5 s) per attempt; both are reported as {@link
 * RateLimitDecision.Unavailable} and counted in {@code unihub_rate_limit_bypassed_total{reason}}.
 * After a failure Redis is left alone for {@code failureCooldown} (the same pause the catalog cache
 * takes, workshop.md): without it every request of an outage would wait the timeout. Requests waved
 * through during the pause are counted too. That state is per pod on purpose: it only decides how
 * soon one pod asks Redis again, never a budget.
 *
 * <p>{@code Retry-After} is the window: Redisson 3.45.1 gives no public time-to-next-permit, and
 * its internal keys are not read.
 */
public final class RedissonRateLimiter implements RateLimiter {

    public static final String BYPASS_COUNTER = "unihub.rate.limit.bypassed";

    /** The {@code reason} label of {@link #BYPASS_COUNTER}: every value is registered at 0. */
    public enum Reason {
        ERROR("error"),
        TIMEOUT("timeout");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RedissonRateLimiter.class);
    private static final String NOT_INITIALIZED = "RateLimiter is not initialized";

    private final RedissonClient redis;
    private final Clock clock;
    private final Duration failureCooldown;
    private final Map<Reason, Counter> bypasses = new EnumMap<>(Reason.class);

    /** Epoch millis until which Redis is not asked; 0 when it is. */
    private final AtomicLong skipUntilMillis = new AtomicLong();

    private volatile Reason skipReason = Reason.ERROR;

    public RedissonRateLimiter(
            RedissonClient redis, Clock clock, Duration failureCooldown, MeterRegistry meters) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failureCooldown = Objects.requireNonNull(failureCooldown, "failureCooldown");
        // Registered at 0 now: Prometheus increase() ignores a series born above 0, so one created
        // by the first bypass could hide the first outage from its alert.
        for (var reason : Reason.values()) {
            bypasses.put(
                    reason,
                    Counter.builder(BYPASS_COUNTER)
                            .tag("reason", reason.tag())
                            .description(
                                    "Requests let through because the rate limiter's Redis did not answer")
                            .register(meters));
        }
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int limit, Duration window) {
        if (clock.millis() < skipUntilMillis.get()) {
            return bypass(skipReason);
        }
        try {
            var limiter = redis.getRateLimiter(key);
            return acquire(limiter, limit, window)
                    ? new RateLimitDecision.Allowed()
                    : new RateLimitDecision.Rejected(window);
        } catch (RedisException failure) {
            var reason = failure instanceof RedisTimeoutException ? Reason.TIMEOUT : Reason.ERROR;
            skipReason = reason;
            skipUntilMillis.set(clock.millis() + failureCooldown.toMillis());
            LOG.warn(
                    "Rate limiter unavailable ({}): request let through, Redis left alone for {}: {}",
                    reason.tag(),
                    failureCooldown,
                    failure.toString());
            return bypass(reason);
        }
    }

    private boolean acquire(RRateLimiter limiter, int limit, Duration window) {
        try {
            return limiter.tryAcquire();
        } catch (RedisException failure) {
            if (!isNotInitialized(failure)) {
                throw failure; // an outage, not an expired key: no second attempt
            }
        }
        // trySetRate is a no-op if another pod set it a moment ago
        limiter.trySetRate(RateType.OVERALL, limit, window, window.multipliedBy(2));
        return limiter.tryAcquire();
    }

    private static boolean isNotInitialized(RedisException failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(NOT_INITIALIZED)) {
                return true;
            }
        }
        return false;
    }

    private RateLimitDecision bypass(Reason reason) {
        bypasses.get(reason).increment();
        return new RateLimitDecision.Unavailable(reason.tag());
    }
}
