package com.unihub.notification.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Notification's view of Workshop (backend-coding-standards.md §7, interaction type 1; Phase 4
 * checklist D35). The adapter calls {@code workshop.api}.
 */
public interface WorkshopDetails {

    /** Empty when the workshop does not exist. */
    Optional<WorkshopFacts> find(UUID workshopId);
}
