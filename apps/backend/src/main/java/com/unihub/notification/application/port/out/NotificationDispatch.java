package com.unihub.notification.application.port.out;

import com.unihub.notification.domain.model.NotificationType;
import java.util.Map;
import java.util.UUID;

/**
 * One delivery, assembled once by the consumer and handed to a channel (notification.md §2).
 *
 * @param userEmail resolved once by the consumer, not re-fetched per channel
 * @param templateContext what the template of {@code type} needs: workshop title, times, location,
 *     recipient name, registration reference; times are {@code Instant}s
 */
public record NotificationDispatch(
        UUID notificationId,
        UUID userId,
        String userEmail,
        NotificationType type,
        Map<String, Object> templateContext) {

    /**
     * The context can carry a ticket's QR payload, which is a credential: the record's own {@code
     * toString} would print it into whatever log line mentions the dispatch. Only the keys are
     * shown.
     */
    @Override
    public String toString() {
        return "NotificationDispatch[notificationId="
                + notificationId
                + ", userId="
                + userId
                + ", type="
                + type
                + ", templateContext keys="
                + templateContext.keySet()
                + "]";
    }
}
