package com.unihub.payment.application.port.out;

/**
 * The confirm may have reached the gateway and no usable answer came back: a read timeout, a reset
 * after sending, a {@code 5xx}, an error that is not a decline, or a status nobody expects after a
 * confirm (payment.md §3). The payment stays {@code PENDING}; the webhook or the reconciliation
 * sweep decides, and the confirm is never sent again.
 */
public final class GatewayOutcomeUnknownException extends RuntimeException {
    public GatewayOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
