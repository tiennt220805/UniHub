package com.unihub.workshop.domain.exception;

import com.unihub.shared.domain.DomainException;
import com.unihub.shared.domain.ProblemKind;
import com.unihub.shared.domain.RetryableUnavailable;
import java.time.Duration;

/**
 * The bucket could not be reached while confirming an upload (workshop.md §7, §10; Phase 5
 * checklist D60): HTTP 503, problem type {@code storage-unavailable}. Nothing was persisted and the
 * pending key is kept, so the same call succeeds once storage is back. An outage of object storage
 * stays inside this one flow (INV-19): no other endpoint ever raises it.
 */
public class BrochureStorageUnavailableException extends DomainException
        implements RetryableUnavailable {
    private static final Duration RETRY_AFTER = Duration.ofSeconds(10);

    public BrochureStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public ProblemKind kind() {
        return ProblemKind.UNAVAILABLE;
    }

    @Override
    public String problemType() {
        return "urn:unihub:problem:storage-unavailable";
    }

    @Override
    public Duration retryAfter() {
        return RETRY_AFTER;
    }
}
