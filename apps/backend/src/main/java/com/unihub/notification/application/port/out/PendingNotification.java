package com.unihub.notification.application.port.out;

import java.util.UUID;

/** A row of an event that is still {@code PENDING}, in the stable order a retry walks them. */
public record PendingNotification(UUID id, UUID userId, String channel, String type) {}
