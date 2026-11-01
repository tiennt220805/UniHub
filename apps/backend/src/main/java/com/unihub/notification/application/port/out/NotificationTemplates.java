package com.unihub.notification.application.port.out;

import com.unihub.notification.domain.model.NotificationType;
import java.util.Map;

/**
 * Renders a notification's content (notification.md §4). A pure function of {@code (type, channel,
 * context)}, which includes whether a ticket is in it; a key the template needs but the context
 * lacks is an {@link IllegalArgumentException}, a template/consumer mismatch caught in testing.
 */
public interface NotificationTemplates {

    /**
     * Context key of the signed QR payload of a ticket. Its mere presence in the context of a
     * {@code REGISTRATION_CONFIRMED} email makes the template add the e-ticket block; the value
     * itself is never written into any text.
     */
    String TICKET_QR_PAYLOAD = "ticketQrPayload";

    /**
     * The {@code Content-ID} the e-ticket block refers to ({@code cid:}) and the email attaches.
     */
    String TICKET_QR_CONTENT_ID = "ticket-qr";

    RenderedNotification render(
            NotificationType type, String channelCode, Map<String, Object> context);
}
