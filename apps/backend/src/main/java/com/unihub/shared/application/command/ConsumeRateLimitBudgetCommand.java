package com.unihub.shared.application.command;

import java.time.Duration;
import java.util.Objects;

/** Use one permit of the budget {@code key}: {@code limit} per {@code window}. */
public record ConsumeRateLimitBudgetCommand(String key, int limit, Duration window) {

    public ConsumeRateLimitBudgetCommand {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(window, "window");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}
