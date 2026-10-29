package com.unihub.notification.domain.exception;

/**
 * Raised at the end of a delivery pass when a row of the event is still {@code PENDING} (a channel
 * was unavailable, a recipient was deferred, or another delivery holds the row's claim). It is a
 * transient failure on purpose: the message walks the retry chain instead of being acked, because
 * acking while a row is {@code PENDING} would lose that notification silently (notification.md §3
 * step 3). Not {@code NonRetryable}, and not "wait for the dependency": notification parks after
 * three retries (messaging.md §4).
 */
public class NotificationsStillPendingException extends RuntimeException {

    public NotificationsStillPendingException(String message) {
        super(message);
    }
}
