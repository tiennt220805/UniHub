package com.unihub.payment.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.RetryableUnavailable;
import java.time.Duration;

/**
 * Nothing could have been charged (payment.md §3): the breaker refused, creating the unconfirmed
 * gateway payment failed, or the confirm never left UniHub. HTTP 503, never recorded under the key,
 * the seat hold untouched; a same-key retry executes for real.
 */
public class PaymentGatewayUnavailableException extends DomainException
        implements RetryableUnavailable {
    /** The breaker's open wait (payment.md §2); an inferred value, as the contract notes. */
    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
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
