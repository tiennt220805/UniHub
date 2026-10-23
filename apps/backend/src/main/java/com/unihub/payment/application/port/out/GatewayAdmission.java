package com.unihub.payment.application.port.out;

/**
 * Whether the gateway may be called at all, asked before the first transaction (checklist D1 G1a,
 * backend-coding-standards.md §19.4). A refusal writes nothing: no payment row, no idempotency key.
 *
 * <p>This only looks. It takes no permission from the circuit breaker, because each gateway call
 * asks for its own; a permission taken here as well and never returned would leak a probe slot of a
 * half-open breaker every time. The circuit may still open between this answer and the call: the
 * charge then undoes itself (G1b).
 */
public interface GatewayAdmission {

    /** {@code false} while the circuit refuses calls. */
    boolean admitting();
}
