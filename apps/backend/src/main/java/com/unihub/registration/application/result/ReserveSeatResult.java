package com.unihub.registration.application.result;

import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;

/**
 * Every decided outcome of a seat reservation. Business rejections are variants, not exceptions, so
 * that idempotency can record and replay them (backend-coding-standards.md §21, §25). Transient
 * failures (lock busy, retries exhausted) are exceptions precisely because they are not recorded.
 */
public sealed interface ReserveSeatResult {

    /** A paid workshop: the seat is held until {@code holdExpiresAt}. */
    record Reserved(RegistrationId id, Instant holdExpiresAt, Instant createdAt, Instant updatedAt)
            implements ReserveSeatResult {}

    /** A free workshop: confirmed at once. */
    record Confirmed(RegistrationId id, Instant createdAt, Instant updatedAt)
            implements ReserveSeatResult {}

    record SoldOut() implements ReserveSeatResult {}

    /** The workshop is not {@code PUBLISHED}: draft, cancelled or completed. */
    record NotOpenForRegistration() implements ReserveSeatResult {}

    /** The student already holds a {@code RESERVED} or {@code CONFIRMED} registration here. */
    record AlreadyRegistered() implements ReserveSeatResult {}
}
