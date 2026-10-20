package com.unihub.shared.application.port.out;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;

/** The same key arrived with a different request body (HTTP 422); nothing is executed. */
public final class IdempotencyKeyReusedException extends DomainException {

    public IdempotencyKeyReusedException(IdempotencyScope scope) {
        super("This Idempotency-Key was already used with a different request body for " + scope);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.UNPROCESSABLE;
    }

    @Override
    public String problemType() {
        // Same value as ProblemTypes.IDEMPOTENCY_KEY_REUSED, which application cannot import.
        return "urn:unihub:problem:idempotency-key-reused";
    }
}
