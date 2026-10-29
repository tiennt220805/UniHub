package com.unihub.notification.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** One row of the in-app inbox as stored: the content was rendered once, when it was created. */
public record InboxEntry(
        UUID id,
        String type,
        String title,
        String body,
        String relatedEntityType,
        UUID relatedEntityId,
        String status,
        Instant sentAt,
        Instant createdAt) {}
