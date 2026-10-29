package com.unihub.notification.application.port.in;

import com.unihub.notification.application.command.DispatchNotificationsCommand;

public interface DispatchNotificationsUseCase {

    /**
     * Creates the rows for the fact and sends every one still {@code PENDING}. Returns normally
     * only when no row of the event is left {@code PENDING}; otherwise it throws so the message is
     * retried.
     *
     * @throws com.unihub.shared.messaging.NonRetryableMessageException when the message names data
     *     that does not exist
     * @throws com.unihub.notification.domain.exception.NotificationsStillPendingException when a
     *     row could not be delivered yet
     */
    void dispatch(DispatchNotificationsCommand command);
}
