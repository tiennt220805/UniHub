package com.unihub.payment.application.port.out;

/**
 * A gateway call that moves no money failed, for any reason (backend-coding-standards.md §19.4).
 * The orchestrator treats it as "nothing was charged" and compensates.
 */
public final class GatewayCallFailedException extends RuntimeException {
    public GatewayCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
