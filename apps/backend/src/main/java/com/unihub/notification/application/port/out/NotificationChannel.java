package com.unihub.notification.application.port.out;

/**
 * One delivery channel, e.g. email or in-app (notification.md §2). A new channel is one new
 * implementation registered as a bean, one additive CHECK value and its configuration; nothing else
 * in the system changes.
 */
public interface NotificationChannel {

    /** The value of {@code notifications.channel} this implementation handles, e.g. "EMAIL". */
    String channelCode();

    /**
     * Attempts delivery. Never throws for an ordinary delivery failure (a bounced email, an
     * unreachable provider): it returns a result the consumer uses to decide the row's fate.
     * Throwing is for a genuinely unexpected condition, which the consumer treats as {@code
     * UNAVAILABLE} for this channel.
     */
    NotificationDeliveryResult send(NotificationDispatch dispatch);
}
