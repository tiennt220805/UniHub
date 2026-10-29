package com.unihub.notification.application.archfixture;

import com.unihub.notification.infrastructure.out.channel.FixtureChannel;

/**
 * An application-layer class of the Notification module that depends on a concrete channel class,
 * which the dispatching rule forbids (M-39). A plain class, never a bean (see {@code ChannelUser}).
 */
public final class ConcreteChannelUser {
    private final FixtureChannel channel = new FixtureChannel();

    public String code() {
        return channel.channelCode();
    }
}
