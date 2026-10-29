package com.unihub.notification.web.mapper;

import com.unihub.notification.application.query.ListMyNotificationsQuery;
import com.unihub.notification.application.result.MyNotificationsResult;
import com.unihub.notification.web.dto.Notification;
import com.unihub.notification.web.dto.NotificationDeliveryStatus;
import com.unihub.notification.web.dto.NotificationListResponse;
import com.unihub.notification.web.dto.NotificationType;
import com.unihub.notification.web.dto.RelatedEntityType;
import com.unihub.shared.security.Actor;
import com.unihub.shared.web.dto.PageMeta;

public final class NotificationWebMapper {

    private NotificationWebMapper() {}

    public static ListMyNotificationsQuery toQuery(
            NotificationDeliveryStatus status, int page, int size, Actor.UserActor caller) {
        return new ListMyNotificationsQuery(
                caller.userId(), status == null ? null : status.name(), page, size);
    }

    public static NotificationListResponse toResponse(MyNotificationsResult result) {
        return new NotificationListResponse(
                result.items().stream()
                        .map(
                                item ->
                                        new Notification(
                                                item.id(),
                                                NotificationType.valueOf(item.type()),
                                                item.title(),
                                                item.body(),
                                                RelatedEntityType.valueOf(item.relatedEntityType()),
                                                item.relatedEntityId(),
                                                NotificationDeliveryStatus.valueOf(item.status()),
                                                item.sentAt(),
                                                item.createdAt()))
                        .toList(),
                new PageMeta(
                        result.page(), result.size(), result.totalElements(), result.totalPages()));
    }
}
