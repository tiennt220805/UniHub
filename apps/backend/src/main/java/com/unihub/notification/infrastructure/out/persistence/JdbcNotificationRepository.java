package com.unihub.notification.infrastructure.out.persistence;

import com.unihub.notification.application.port.out.NewNotification;
import com.unihub.notification.application.port.out.NotificationRepository;
import com.unihub.notification.application.port.out.PendingNotification;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tier B persistence of {@code notifications} (backend-coding-standards.md §9.1): one statement per
 * operation, each guarded by the status it expects, so no ordering of concurrent deliveries can
 * move a row backwards or send it twice (notification.md §3, Phase 4 checklist D31, D33).
 */
@Repository
class JdbcNotificationRepository implements NotificationRepository {

    private static final String INSERT =
            "INSERT INTO notifications (user_id, channel, type, related_entity_type,"
                    + " related_entity_id, source_event_id, title, body)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (source_event_id, user_id, channel) DO NOTHING";

    private static final String FIND_PENDING =
            "SELECT id, user_id, channel, type FROM notifications"
                    + " WHERE source_event_id = ? AND status = 'PENDING' ORDER BY id";

    private static final String CLAIM =
            "UPDATE notifications SET claimed_at = now() WHERE id = ? AND status = 'PENDING'"
                    + " AND (claimed_at IS NULL OR claimed_at < now() - make_interval(secs => ?))"
                    + " RETURNING claimed_at";

    private final JdbcTemplate jdbc;

    JdbcNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insertIfAbsent(List<NewNotification> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        var counts =
                jdbc.batchUpdate(
                        INSERT,
                        rows,
                        rows.size(),
                        (ps, row) -> {
                            ps.setObject(1, row.userId());
                            ps.setString(2, row.channel());
                            ps.setString(3, row.type());
                            ps.setString(4, row.relatedEntityType());
                            ps.setObject(5, row.relatedEntityId());
                            ps.setObject(6, row.sourceEventId());
                            ps.setString(7, row.title());
                            ps.setString(8, row.body());
                        });
        var inserted = 0;
        for (var batch : counts) {
            for (var count : batch) {
                inserted += Math.max(count, 0);
            }
        }
        return inserted;
    }

    @Override
    public List<PendingNotification> findPending(UUID sourceEventId) {
        return jdbc.query(
                FIND_PENDING,
                (rs, rowNum) ->
                        new PendingNotification(
                                rs.getObject("id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                rs.getString("channel"),
                                rs.getString("type")),
                sourceEventId);
    }

    @Override
    public int countPending(UUID sourceEventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE source_event_id = ?"
                        + " AND status = 'PENDING'",
                Integer.class,
                sourceEventId);
    }

    @Override
    public Optional<Instant> claim(UUID notificationId, Duration lease) {
        // Seconds as a double: make_interval(secs => …) takes one, and a test lease is sub-second.
        var seconds = lease.toNanos() / 1_000_000_000.0;
        return jdbc
                .query(
                        CLAIM,
                        (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant(),
                        notificationId,
                        seconds)
                .stream()
                .findFirst();
    }

    @Override
    public void release(UUID notificationId, Instant claimToken) {
        jdbc.update(
                "UPDATE notifications SET claimed_at = NULL WHERE id = ? AND status = 'PENDING'"
                        + " AND claimed_at = ?",
                notificationId,
                OffsetDateTime.ofInstant(claimToken, ZoneOffset.UTC));
    }

    @Override
    public void markSent(UUID notificationId) {
        jdbc.update(
                "UPDATE notifications SET status = 'SENT', sent_at = now() WHERE id = ?"
                        + " AND status = 'PENDING'",
                notificationId);
    }

    @Override
    public void markFailed(UUID notificationId) {
        jdbc.update(
                "UPDATE notifications SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'",
                notificationId);
    }

    @Override
    public int failPending(UUID sourceEventId) {
        return jdbc.update(
                "UPDATE notifications SET status = 'FAILED' WHERE source_event_id = ?"
                        + " AND status = 'PENDING'",
                sourceEventId);
    }

    @Override
    public Optional<Instant> oldestPendingCreatedAt() {
        var oldest =
                jdbc.queryForObject(
                        "SELECT min(created_at) FROM notifications WHERE status = 'PENDING'",
                        OffsetDateTime.class);
        return Optional.ofNullable(oldest).map(OffsetDateTime::toInstant);
    }
}
