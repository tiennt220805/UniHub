package com.unihub.notification.application.result;

import com.unihub.notification.application.port.out.InboxPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A page of the caller's in-app notifications. */
public record MyNotificationsResult(
        List<Item> items, int page, int size, long totalElements, int totalPages) {

    public record Item(
            UUID id,
            String type,
            String title,
            String body,
            String relatedEntityType,
            UUID relatedEntityId,
            String status,
            Instant sentAt,
            Instant createdAt) {}

    public static MyNotificationsResult of(InboxPage page) {
        return new MyNotificationsResult(
                page.items().stream()
                        .map(
                                e ->
                                        new Item(
                                                e.id(),
                                                e.type(),
                                                e.title(),
                                                e.body(),
                                                e.relatedEntityType(),
                                                e.relatedEntityId(),
                                                e.status(),
                                                e.sentAt(),
                                                e.createdAt()))
                        .toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
