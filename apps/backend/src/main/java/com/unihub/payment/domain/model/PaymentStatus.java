package com.unihub.payment.domain.model;

/**
 * Mirrors the CHECK on {@code payments.status} (database-schema.md §5.1), all six values, so that
 * {@link Payment#restore} can read any valid row. The aggregate implements only the transitions out
 * of {@code PENDING} today; the refund transitions arrive with the compensation branch.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUND_REQUIRED,
    REFUNDED;

    /**
     * Whether a payment in this status counts toward "at most one live payment per registration"
     * ({@code uq_payments_live_per_registration}, Phase 2 checklist D5).
     */
    public boolean isLive() {
        return this == PENDING || this == SUCCEEDED;
    }
}
