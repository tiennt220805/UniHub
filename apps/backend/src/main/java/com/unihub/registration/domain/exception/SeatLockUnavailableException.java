package com.unihub.registration.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.RetryableUnavailable;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;

/**
 * The per-workshop seat lock could not be taken in time (and, from Phase 3, Redis is unreachable).
 * Never proceed without the lock; the request is not recorded under its idempotency key.
 */
public class SeatLockUnavailableException extends DomainException implements RetryableUnavailable {
    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    public SeatLockUnavailableException(WorkshopId workshopId) {
        super("Seat lock for workshop " + workshopId.value() + " is busy");
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.UNAVAILABLE;
    }

    @Override
    public Duration retryAfter() {
        return RETRY_AFTER;
    }
}
