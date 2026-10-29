package com.unihub.notification.application.usecase;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Resolves a channel code to its {@link NotificationChannel} (notification.md §2). Spring collects
 * every channel bean; this class re-keys them once. It names no concrete channel.
 */
@Component
public class NotificationDispatcher {

    private final Map<String, NotificationChannel> channelsByCode = new HashMap<>();

    NotificationDispatcher(List<NotificationChannel> channels) {
        for (var channel : channels) {
            if (channelsByCode.put(channel.channelCode(), channel) != null) {
                throw new IllegalStateException(
                        "Two NotificationChannel beans for " + channel.channelCode());
            }
        }
    }

    NotificationDeliveryResult dispatch(String channelCode, NotificationDispatch dispatch) {
        var channel = channelsByCode.get(channelCode);
        if (channel == null) {
            // notifications.channel's CHECK already keeps an unknown value out; this guards a CHECK
            // value added without its bean (schema/code drift), not an expected path.
            throw new IllegalStateException(
                    "No NotificationChannel registered for: " + channelCode);
        }
        return channel.send(dispatch);
    }

    /** The codes that have a bean; configuration naming any other fails the start. */
    public Set<String> channelCodes() {
        return new TreeSet<>(channelsByCode.keySet());
    }
}
