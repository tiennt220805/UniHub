package com.unihub.notification.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Notification's view of Registration (backend-coding-standards.md §7, interaction type 1; Phase 4
 * checklist D35). The adapter calls {@code registration.api}.
 */
public interface WorkshopRecipients {

    /** The workshop's registrations that hold a seat ({@code CONFIRMED}, {@code RESERVED}). */
    List<RegistrationRef> holdingSeats(UUID workshopId);

    /** One registration in any status; empty when it does not exist. */
    Optional<RegistrationRef> find(UUID registrationId);
}
