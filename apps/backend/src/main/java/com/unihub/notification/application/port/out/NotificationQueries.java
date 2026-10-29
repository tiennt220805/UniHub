package com.unihub.notification.application.port.out;

import java.util.UUID;

/** Read model of the in-app inbox (backend-coding-standards.md §20). */
public interface NotificationQueries {

    /**
     * The user's {@code IN_APP} notifications, newest first (the id breaks ties so a page boundary
     * is stable).
     *
     * @param status only this delivery status, or null for all
     */
    InboxPage inboxOf(UUID userId, String status, int page, int size);
}
