package com.unihub.shared.domain;

import java.time.Duration;

/** Implemented by an {@code UNAVAILABLE} {@link DomainException} that carries a Retry-After. */
public interface RetryableUnavailable {

    Duration retryAfter();
}
