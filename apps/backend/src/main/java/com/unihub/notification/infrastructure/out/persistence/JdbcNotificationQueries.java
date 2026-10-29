package com.unihub.notification.infrastructure.out.persistence;

import com.unihub.notification.application.port.out.InboxEntry;
import com.unihub.notification.application.port.out.InboxPage;
import com.unihub.notification.application.port.out.NotificationQueries;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The inbox read, served by {@code idx_notifications_inbox (user_id, channel, created_at DESC)}.
 * Always filtered by the user and by {@code channel = 'IN_APP'}: an {@code EMAIL} row is read in
 * the student's mail client, not here.
 */
@Repository
class JdbcNotificationQueries implements NotificationQueries {

    private static final String WHERE_USER_AND_STATUS =
            " WHERE user_id = ? AND channel = 'IN_APP' AND (?::text IS NULL OR status = ?)";

    private final JdbcTemplate jdbc;

    JdbcNotificationQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InboxPage inboxOf(UUID userId, String status, int page, int size) {
        var total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM notifications" + WHERE_USER_AND_STATUS,
                        Long.class,
                        userId,
                        status,
                        status);
        List<InboxEntry> items =
                jdbc.query(
                        "SELECT id, type, title, body, related_entity_type, related_entity_id,"
                                + " status, sent_at, created_at FROM notifications"
                                + WHERE_USER_AND_STATUS
                                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                        (rs, rowNum) ->
                                new InboxEntry(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("type"),
                                        rs.getString("title"),
                                        rs.getString("body"),
                                        rs.getString("related_entity_type"),
                                        rs.getObject("related_entity_id", UUID.class),
                                        rs.getString("status"),
                                        instant(rs.getObject("sent_at", OffsetDateTime.class)),
                                        instant(rs.getObject("created_at", OffsetDateTime.class))),
                        userId,
                        status,
                        status,
                        size,
                        (long) page * size);
        var totalPages = (int) ((total + size - 1) / size);
        return new InboxPage(items, page, size, total, totalPages);
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
