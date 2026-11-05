package com.unihub.shared.application.result;

/**
 * Whether a request may go ahead. When it may not, {@code retryAfterSeconds} is the whole seconds
 * to put in {@code Retry-After}: at least 1.
 */
public record RateLimitOutcome(boolean allowed, long retryAfterSeconds) {

    private static final RateLimitOutcome ALLOWED = new RateLimitOutcome(true, 0);

    public static RateLimitOutcome permit() {
        return ALLOWED;
    }

    public static RateLimitOutcome reject(long retryAfterSeconds) {
        return new RateLimitOutcome(false, Math.max(1, retryAfterSeconds));
    }
}
