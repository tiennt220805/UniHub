package com.unihub.shared.domain;

import java.util.UUID;

/** A repository lost a version race: the aggregate changed since it was loaded (§9.2). */
public final class OptimisticConflictException extends DomainException {

    public OptimisticConflictException(String aggregateType, UUID aggregateId) {
        super(aggregateType + " " + aggregateId + " was modified concurrently");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.CONFLICT;
    }
}
