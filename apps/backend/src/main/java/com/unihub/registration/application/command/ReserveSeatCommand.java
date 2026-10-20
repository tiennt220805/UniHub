package com.unihub.registration.application.command;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import java.util.Objects;

/**
 * Take a seat in a workshop for the authenticated student. The idempotency key and request
 * fingerprint join this command with the idempotency step (checklist 3.3).
 */
public record ReserveSeatCommand(Actor.UserActor actor, WorkshopId workshopId) {

    public ReserveSeatCommand {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(workshopId);
    }
}
