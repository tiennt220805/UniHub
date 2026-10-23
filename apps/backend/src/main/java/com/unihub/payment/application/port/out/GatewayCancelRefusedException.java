package com.unihub.payment.application.port.out;

/**
 * The gateway refused to cancel a payment because it is no longer in a cancellable state — most
 * importantly because it already succeeded. Not a failure of the call: the caller asks the gateway
 * again what the payment is and resolves from that (payment.md §6).
 */
public final class GatewayCancelRefusedException extends RuntimeException {
    public GatewayCancelRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
