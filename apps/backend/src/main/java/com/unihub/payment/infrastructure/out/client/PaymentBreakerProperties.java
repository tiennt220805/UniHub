package com.unihub.payment.infrastructure.out.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.payment.breaker.*} (payment.md §2, §3, §5; checklist D12, D17). One circuit breaker
 * for every gateway call; the time limit and the retries are per call-site. Everything has the
 * documented default, so only a test needs to set any of them (a shorter open state, a lower
 * minimum number of calls). {@code minimumNumberOfCalls} is 10 and recalibrated in Phase 9.
 *
 * @param windowSize calls the failure rate is judged over (count-based)
 * @param failureRateThreshold percent of the window that failing opens the circuit
 * @param waitDurationInOpenState how long the circuit refuses calls before probing again
 * @param permittedCallsInHalfOpenState probe calls let through while probing
 * @param minimumNumberOfCalls calls recorded before the failure rate is evaluated at all
 * @param syncTimeLimit the time limit of each call of the charge (create, confirm)
 * @param asyncTimeLimit the time limit of each call reconciliation makes (retrieve, cancel)
 * @param asyncRetryAttempts attempts a retrieve gets: the first plus its retries
 * @param asyncRetryBackoff pause before the first retry; it doubles each time
 */
@ConfigurationProperties(prefix = "unihub.payment.breaker")
public record PaymentBreakerProperties(
        Integer windowSize,
        Float failureRateThreshold,
        Duration waitDurationInOpenState,
        Integer permittedCallsInHalfOpenState,
        Integer minimumNumberOfCalls,
        Duration syncTimeLimit,
        Duration asyncTimeLimit,
        Integer asyncRetryAttempts,
        Duration asyncRetryBackoff) {

    public PaymentBreakerProperties {
        windowSize = windowSize == null ? 20 : windowSize;
        failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
        waitDurationInOpenState =
                waitDurationInOpenState == null ? Duration.ofSeconds(30) : waitDurationInOpenState;
        permittedCallsInHalfOpenState =
                permittedCallsInHalfOpenState == null ? 5 : permittedCallsInHalfOpenState;
        minimumNumberOfCalls = minimumNumberOfCalls == null ? 10 : minimumNumberOfCalls;
        syncTimeLimit = syncTimeLimit == null ? Duration.ofSeconds(3) : syncTimeLimit;
        asyncTimeLimit = asyncTimeLimit == null ? Duration.ofSeconds(10) : asyncTimeLimit;
        // 1 + 3 retries, pausing 1 s, 2 s, 4 s: the ~47 s worst case payment.md §2 works out.
        asyncRetryAttempts = asyncRetryAttempts == null ? 4 : asyncRetryAttempts;
        asyncRetryBackoff = asyncRetryBackoff == null ? Duration.ofSeconds(1) : asyncRetryBackoff;
        if (minimumNumberOfCalls > windowSize) {
            throw new IllegalArgumentException(
                    "unihub.payment.breaker.minimum-number-of-calls cannot exceed the window size");
        }
        if (asyncRetryAttempts < 1) {
            throw new IllegalArgumentException(
                    "unihub.payment.breaker.async-retry-attempts must be at least 1");
        }
        requirePositive("sync-time-limit", syncTimeLimit);
        requirePositive("async-time-limit", asyncTimeLimit);
        requirePositive("async-retry-backoff", asyncRetryBackoff);
        requirePositive("wait-duration-in-open-state", waitDurationInOpenState);
    }

    private static void requirePositive(String name, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                    "unihub.payment.breaker." + name + " must be positive");
        }
    }
}
