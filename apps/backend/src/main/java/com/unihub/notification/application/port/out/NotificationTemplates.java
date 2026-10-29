package com.unihub.notification.application.port.out;

import com.unihub.notification.domain.model.NotificationType;
import java.util.Map;

/**
 * Renders a notification's content (notification.md §4). A pure function of {@code (type, channel,
 * context)}; a key the template needs but the context lacks is an {@link IllegalArgumentException},
 * a template/consumer mismatch caught in testing.
 */
public interface NotificationTemplates {

    RenderedNotification render(
            NotificationType type, String channelCode, Map<String, Object> context);
}
