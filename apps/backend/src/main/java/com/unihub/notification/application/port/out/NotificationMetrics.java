package com.unihub.notification.application.port.out;

/** Delivery counters (Phase 4 checklist 2.6); exported and alerted on in Phase 6. */
public interface NotificationMetrics {

    /** One delivery attempt ended with {@code result} on {@code channel}. */
    void delivered(String channel, NotificationDeliveryResult result);

    /** A channel threw instead of returning a result; counted as {@code UNAVAILABLE} too. */
    void channelThrew(String channel);
}
