package com.unihub.shared.application.port.out;

/**
 * Which operation an idempotency key guards (database-schema.md §8.1). The same client key may be
 * reused across scopes without colliding. Values are stored verbatim in {@code
 * idempotency_keys.scope}.
 */
public enum IdempotencyScope {
    REGISTRATION_CREATE,
    PAYMENT_CHARGE
}
