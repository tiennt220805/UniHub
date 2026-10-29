package com.unihub.notification.application.port.out;

/**
 * What a channel reports for one {@link NotificationDispatch} (notification.md §2, Phase 4
 * checklist D32). It says whether trying again could help and whether the failure belongs to this
 * recipient or to the whole channel.
 */
public enum NotificationDeliveryResult {
    /** The provider accepted the message. */
    SENT,
    /** A permanent refusal for this one recipient. The row becomes {@code FAILED}. */
    REJECTED,
    /** A transient refusal for this one recipient. The channel carries on with the next row. */
    DEFERRED,
    /** The channel itself failed, for every recipient alike. Its remaining rows are skipped. */
    UNAVAILABLE
}
