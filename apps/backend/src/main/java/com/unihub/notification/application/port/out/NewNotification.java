package com.unihub.notification.application.port.out;

import java.util.UUID;

/**
 * A row to insert in transaction 1 of a delivery, {@code PENDING}. {@code title} and {@code body}
 * are filled only for {@code IN_APP}, rendered once here and never again (notification.md §1).
 */
public record NewNotification(
        UUID sourceEventId,
        UUID userId,
        String channel,
        String type,
        String relatedEntityType,
        UUID relatedEntityId,
        String title,
        String body) {}
