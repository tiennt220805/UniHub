package com.unihub.payment.application.port.out;

/** What an operator watches reconciliation by (payment.md §6, observability.md §4). */
public interface ReconciliationMetrics {

    /**
     * A payment still {@code PENDING} at the gateway's {@code processing} 24 hours after it was
     * initiated: reconciliation stops acting on it and an operator takes over ({@code
     * unihub_payment_reconciliation_stuck_total}).
     */
    void stuck();
}
