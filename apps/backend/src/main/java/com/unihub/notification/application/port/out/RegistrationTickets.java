package com.unihub.notification.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Notification's view of a registration's ticket (backend-coding-standards.md §7, interaction type
 * 1; Phase 5 checklist D55). The adapter calls {@code registration.api}.
 */
public interface RegistrationTickets {

    /**
     * The signed QR payload of the registration; empty when it has no ticket (confirmed before
     * Phase 5) or does not exist. Never logged and never stored in a notification.
     */
    Optional<String> qrPayloadOf(UUID registrationId);
}
