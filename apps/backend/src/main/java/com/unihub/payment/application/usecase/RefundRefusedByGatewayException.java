package com.unihub.payment.application.usecase;

import com.unihub.shared.messaging.NonRetryableMessageException;

/**
 * The gateway has the refund and it is final and bad ({@code failed} or {@code canceled}). Asking
 * again cannot change that, so the message goes to the parking lot at once; the payment stays
 * {@code REFUND_REQUIRED} for an operator (payment.md §9).
 */
final class RefundRefusedByGatewayException extends NonRetryableMessageException {

    RefundRefusedByGatewayException(String gatewayTransactionId) {
        super("the gateway failed or cancelled the refund of " + gatewayTransactionId);
    }
}
