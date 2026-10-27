package com.unihub.payment.application.usecase;

/**
 * The gateway has the refund and it is not final yet ({@code pending} or {@code requires_action}).
 * Transient: the retry chain asks again, and it is parked only if it is still not final after the
 * chain's three attempts, the payment staying {@code REFUND_REQUIRED} (payment.md §9).
 */
final class RefundNotFinalException extends RuntimeException {

    RefundNotFinalException(String gatewayTransactionId) {
        super("the refund of " + gatewayTransactionId + " is not final yet");
    }
}
