package com.unihub.registration.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.RetryableUnavailable;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Duration;

/**
 * Every seat-claim attempt lost the {@code workshops.version} race (D3, C16). Transient: the seat
 * may still be free, so the answer is {@code 503}, never {@code 409}.
 */
public class SeatTemporarilyUnavailableException extends DomainException
        implements RetryableUnavailable {
    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    public SeatTemporarilyUnavailableException(WorkshopId workshopId, Throwable cause) {
        super("Workshop " + workshopId.value() + " kept changing during the seat claim", cause);
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
