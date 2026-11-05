package com.unihub.shared.application.port.out;

import java.time.Duration;
import java.util.Objects;

/** The answer of {@link RateLimiter#tryAcquire}. */
public sealed interface RateLimitDecision {

    /** A permit was used; the request may go ahead. */
    record Allowed() implements RateLimitDecision {}

    /**
     * The budget is spent. {@code retryAfter} is how long to wait before asking again: an upper
     * bound (the window), not the exact moment the next permit frees.
     */
    record Rejected(Duration retryAfter) implements RateLimitDecision {
        public Rejected {
            Objects.requireNonNull(retryAfter, "retryAfter");
        }
    }

    /**
     * The store did not answer, so no decision was made. {@code reason} is a metric label value.
     */
    record Unavailable(String reason) implements RateLimitDecision {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
