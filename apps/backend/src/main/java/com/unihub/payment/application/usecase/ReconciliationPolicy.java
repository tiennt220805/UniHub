package com.unihub.payment.application.usecase;

import java.time.Duration;

/**
 * Reconciliation's tunables as a plain value (built from {@code ReconciliationProperties}): how
 * long a payment may stay {@code PENDING} before the sweep takes it (15 min, docs/requirement.md
 * §2.10), how many one sweep takes, and how long a payment the gateway keeps "processing" is worked
 * on before an operator is told (24 h, Stripe's idempotency-key window) — payment.md §6.
 */
public record ReconciliationPolicy(Duration staleAfter, int sweepBatchSize, Duration stuckAfter) {

    public ReconciliationPolicy {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        if (sweepBatchSize < 1) {
            throw new IllegalArgumentException("sweepBatchSize must be at least 1");
        }
        if (stuckAfter == null || stuckAfter.compareTo(staleAfter) <= 0) {
            throw new IllegalArgumentException("stuckAfter must be longer than staleAfter");
        }
    }
}
