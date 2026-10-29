package com.unihub.notification.application.usecase;

import com.unihub.notification.application.port.out.NewNotification;
import com.unihub.notification.application.port.out.NotificationRepository;
import com.unihub.notification.application.port.out.PendingNotification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions of a delivery (notification.md §3): the insert of step 1, and the claim,
 * release and mark around each send. A separate bean so the use case, which holds no transaction
 * while it calls a provider (backend-coding-standards.md §19.1), reaches them through the Spring
 * proxy. Read-write, so they run at the Master.
 */
@Component
class NotificationRows {
    private final NotificationRepository notifications;

    NotificationRows(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional
    List<PendingNotification> findPending(UUID sourceEventId) {
        return notifications.findPending(sourceEventId);
    }

    @Transactional
    int countPending(UUID sourceEventId) {
        return notifications.countPending(sourceEventId);
    }

    @Transactional
    int insertIfAbsent(List<NewNotification> rows) {
        return notifications.insertIfAbsent(rows);
    }

    @Transactional
    Optional<Instant> claim(UUID notificationId, Duration lease) {
        return notifications.claim(notificationId, lease);
    }

    @Transactional
    void release(UUID notificationId, Instant claimToken) {
        notifications.release(notificationId, claimToken);
    }

    @Transactional
    void markSent(UUID notificationId) {
        notifications.markSent(notificationId);
    }

    @Transactional
    void markFailed(UUID notificationId) {
        notifications.markFailed(notificationId);
    }

    @Transactional
    int failPending(UUID sourceEventId) {
        return notifications.failPending(sourceEventId);
    }
}
