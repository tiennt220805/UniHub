package com.unihub.registration.application.command;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import java.util.Objects;

/**
 * Take a seat in a workshop for the authenticated student. {@code idempotencyKey} is the {@code
 * Idempotency-Key} header's UUID in canonical lowercase form, {@code requestFingerprint} the hash
 * of the request body (backend-coding-standards.md §21 rules 3-4); the actor is never read from the
 * request.
 */
public record ReserveSeatCommand(
        Actor.UserActor actor,
        WorkshopId workshopId,
        String idempotencyKey,
        String requestFingerprint) {

    public ReserveSeatCommand {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(workshopId);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(requestFingerprint);
    }
}
