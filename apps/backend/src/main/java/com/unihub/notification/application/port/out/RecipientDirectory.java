package com.unihub.notification.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Notification's view of User (backend-coding-standards.md §7, interaction type 1; Phase 4
 * checklist D35). The adapter calls {@code user.api}.
 */
public interface RecipientDirectory {

    Optional<Recipient> find(UUID userId);

    /** Users that exist among {@code userIds}, keyed by id; the rest are simply absent. */
    Map<UUID, Recipient> findAll(Collection<UUID> userIds);
}
