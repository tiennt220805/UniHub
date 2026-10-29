package com.unihub.notification.infrastructure.out.channel;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import org.springframework.stereotype.Component;

/**
 * The in-app inbox. The row already carries its rendered {@code title} and {@code body}
 * (notification.md §1), written in transaction 1 of the delivery, so "sending" it is nothing more
 * than reporting {@code SENT}: the consumer then marks the row. It writes only to this module's own
 * database, so it can never be {@code UNAVAILABLE}.
 */
@Component
class InAppNotificationChannel implements NotificationChannel {
    static final String CODE = "IN_APP";

    @Override
    public String channelCode() {
        return CODE;
    }

    @Override
    public NotificationDeliveryResult send(NotificationDispatch dispatch) {
        return NotificationDeliveryResult.SENT;
    }
}
