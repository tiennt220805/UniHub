package com.unihub.payment.application.port.out;

/**
 * A gateway call that cannot move money failed, for any reason (backend-coding-standards.md §19.4).
 * At charge time the orchestrator treats it as "nothing was charged" and compensates; a read or a
 * cancel made by reconciliation lets it reach the broker's retry chain, so it is asked again.
 */
public final class GatewayCallFailedException extends RuntimeException {
    public GatewayCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
