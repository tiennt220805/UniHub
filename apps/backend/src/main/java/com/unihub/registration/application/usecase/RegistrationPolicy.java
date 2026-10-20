package com.unihub.registration.application.usecase;

import java.time.Duration;

/**
 * Registration's tunables as a plain value (built from {@code RegistrationProperties}): the seat
 * hold (15 min), the lock wait and lease (2 s / 5 s) and the seat-claim attempts (3) —
 * registration.md §2.
 */
public record RegistrationPolicy(
        Duration seatHoldDuration, Duration lockWait, Duration lockLease, int seatClaimAttempts) {

    public RegistrationPolicy {
        if (seatClaimAttempts < 1) {
            throw new IllegalArgumentException("seatClaimAttempts must be at least 1");
        }
    }
}
