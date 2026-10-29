package com.unihub.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Delivery status, not read status: there is no read/unread state (D38). */
@Schema(enumAsRef = true)
public enum NotificationDeliveryStatus {
    PENDING,
    SENT,
    FAILED
}
