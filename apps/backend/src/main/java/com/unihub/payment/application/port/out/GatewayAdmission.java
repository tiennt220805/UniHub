package com.unihub.payment.application.port.out;

/**
 * Permission to call the gateway at all, asked before the first transaction (checklist D1 G1a,
 * backend-coding-standards.md §19.4). The circuit breaker (checklist 3.7) is the real adapter;
 * until then everything is admitted. A refusal writes nothing: no payment row, no idempotency key.
 *
 * <p>A permission that was granted and not used up by a gateway call must be given back, so a
 * half-open probe slot is never leaked.
 */
public interface GatewayAdmission {

    boolean tryAcquire();

    /** Gives back a permission that no gateway call used. */
    void release();
}
