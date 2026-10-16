package com.unihub.shared.application.port.out;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;

/** The same key is already being processed (HTTP 409). Safe for the client to retry later. */
public final class IdempotencyKeyInUseException extends DomainException {

    public IdempotencyKeyInUseException(IdempotencyScope scope) {
        super("A request with this Idempotency-Key is already being processed for " + scope);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }
}
