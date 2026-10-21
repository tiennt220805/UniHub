package com.unihub.payment.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;

/**
 * The webhook's signature does not prove it came from the gateway (HTTP 400, payment.md §7 step 1).
 * The message says nothing about why, and never echoes the body: nothing of an unverified request
 * is trusted, not even in an error.
 */
public class InvalidWebhookSignatureException extends DomainException {
    public InvalidWebhookSignatureException() {
        super("The webhook signature is invalid.");
    }

    public InvalidWebhookSignatureException(Throwable cause) {
        super("The webhook signature is invalid.", cause);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.INVALID_REQUEST;
    }
}
