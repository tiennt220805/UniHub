package com.unihub.registration.application.command;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.Objects;

/**
 * The hold of this registration is due: release its seat if it is still {@code RESERVED} and its
 * time is up. {@code workshopId} names the lock to take before anything is read, and is checked
 * against the registration's own workshop once it is.
 */
public record ExpireSeatHoldCommand(RegistrationId registrationId, WorkshopId workshopId) {

    public ExpireSeatHoldCommand {
        Objects.requireNonNull(registrationId);
        Objects.requireNonNull(workshopId);
    }
}
