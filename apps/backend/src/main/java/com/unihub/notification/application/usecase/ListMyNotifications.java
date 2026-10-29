package com.unihub.notification.application.usecase;

import com.unihub.notification.application.port.in.ListMyNotificationsUseCase;
import com.unihub.notification.application.port.out.NotificationQueries;
import com.unihub.notification.application.query.ListMyNotificationsQuery;
import com.unihub.notification.application.result.MyNotificationsResult;
import com.unihub.shared.application.routing.ReadFromPrimary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own in-app inbox. From the Master: a student opens it right after the event that
 * produced a notification, and a lagging Replica could still show the inbox as it was.
 */
@Service
class ListMyNotifications implements ListMyNotificationsUseCase {
    private final NotificationQueries queries;

    ListMyNotifications(NotificationQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public MyNotificationsResult list(ListMyNotificationsQuery query) {
        return MyNotificationsResult.of(
                queries.inboxOf(
                        query.caller().value(), query.status(), query.page(), query.size()));
    }
}
