package com.unihub.notification.infrastructure.out.channel;

import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;

/**
 * A concrete channel class in the channel package, for {@code NotificationChannelRulesTest}: the
 * thing {@link com.unihub.notification.application.archfixture.ConcreteChannelUser} must not depend
 * on. A plain class, never a bean (see {@code ChannelUser}).
 */
public final class FixtureChannel implements NotificationChannel {
    @Override
    public String channelCode() {
        return "FIXTURE";
    }

    @Override
    public NotificationDeliveryResult send(NotificationDispatch dispatch) {
        return NotificationDeliveryResult.SENT;
    }
}
