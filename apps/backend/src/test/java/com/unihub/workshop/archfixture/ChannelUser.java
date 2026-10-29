package com.unihub.workshop.archfixture;

import com.unihub.notification.application.port.out.NotificationChannel;

/**
 * A class of another module that names a notification channel: exactly what the M-39 rule forbids
 * (Phase 4 D40, AC40.1). Deliberately a plain class, no annotations: test sources under {@code
 * com.unihub} are component-scanned into every Spring context, and this must never be a bean.
 * {@code NotificationChannelRulesTest} imports it by name; the production ArchUnit suites do not
 * include test classes.
 */
public final class ChannelUser {
    private final NotificationChannel channel;

    public ChannelUser(NotificationChannel channel) {
        this.channel = channel;
    }

    public String code() {
        return channel.channelCode();
    }
}
