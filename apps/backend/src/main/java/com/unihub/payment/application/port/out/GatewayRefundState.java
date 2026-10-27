package com.unihub.payment.application.port.out;

/**
 * What the gateway says about the refund of a payment (payment.md §9, checklist D25), reduced to
 * what this system acts on. Stripe's {@code Refund.status} has five values; only {@code succeeded}
 * is final and good.
 */
public enum GatewayRefundState {
    /** {@code succeeded}: the money is back. */
    SUCCEEDED,
    /** {@code pending} or {@code requires_action}: not final yet, asked again later. */
    PENDING,
    /** {@code failed} or {@code canceled}: final and bad, an operator decides. */
    FAILED
}
