package com.unihub.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// enumAsRef: the contract declares NotificationType as a named component
// (schemas/notification.yaml); springdoc inlines enums unless told otherwise.
@Schema(enumAsRef = true)
public enum NotificationType {
    REGISTRATION_CONFIRMED,
    WORKSHOP_CANCELLED,
    WORKSHOP_RESCHEDULED
}
