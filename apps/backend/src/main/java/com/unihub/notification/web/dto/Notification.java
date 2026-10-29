package com.unihub.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * packages/api-contract {@code Notification}: one row of the in-app inbox, with the title and body
 * rendered once when it was created. The {@code sentAt ⇔ SENT} if/then/else is added by {@code
 * NotificationOpenApiConfig}.
 */
@Schema(
        types = {"object"},
        requiredProperties = {
            "id",
            "type",
            "title",
            "body",
            "relatedEntityType",
            "relatedEntityId",
            "status",
            "createdAt"
        })
public record Notification(
        UUID id,
        NotificationType type,
        @Schema(
                        types = {"string"},
                        maxLength = 200)
                String title,
        @Schema(types = {"string"}) String body,
        RelatedEntityType relatedEntityType,
        UUID relatedEntityId,
        NotificationDeliveryStatus status,
        @Schema(types = {"string", "null"}) Instant sentAt,
        Instant createdAt) {}
