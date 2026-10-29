package com.unihub.notification.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves migration {@code V5} gives {@code notifications} the redelivery dedup key the consumer
 * relies on (INV-11; Phase 4 checklist D31, D33, D38; database-schema.md §7.1, §10.2).
 */
class NotificationSchemaIT extends RolledBackJdbcSupport {

    private static final String DEDUP_KEY = "uq_notifications_source_event_user_channel";

    @Test
    @DisplayName("a second row for the same (event, user, channel) is rejected")
    void sameEventUserChannel_isRejected() {
        var user = insertUser();
        var event = UUID.randomUUID();
        insertNotification(event, user, "EMAIL");

        assertRejected(() -> insertNotification(event, user, "EMAIL"), UNIQUE_VIOLATION, DEDUP_KEY);
    }

    @Test
    @DisplayName("one event fans out: other users and the other channel are separate rows")
    void oneEvent_fansOutToManyRecipientsAndChannels() {
        var event = UUID.randomUUID();
        var first = insertUser();
        var second = insertUser();

        insertNotification(event, first, "EMAIL");
        insertNotification(event, first, "IN_APP");
        insertNotification(event, second, "EMAIL");
        insertNotification(event, second, "IN_APP");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE source_event_id = ?",
                                Integer.class,
                                event))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("two events about the same workshop and user are two notifications")
    void twoEventsSameEntity_areBothKept() {
        var user = insertUser();
        var workshop = UUID.randomUUID();

        insertNotification(UUID.randomUUID(), user, "IN_APP", workshop);
        insertNotification(UUID.randomUUID(), user, "IN_APP", workshop);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE user_id = ?",
                                Integer.class,
                                user))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("ON CONFLICT DO NOTHING absorbs a redelivered insert and leaves one row")
    void onConflictDoNothing_absorbsRedelivery() {
        var user = insertUser();
        var event = UUID.randomUUID();
        var sql =
                "INSERT INTO notifications (user_id, channel, type, related_entity_type,"
                        + " related_entity_id, source_event_id) VALUES (?, 'EMAIL',"
                        + " 'WORKSHOP_CANCELLED', 'WORKSHOP', ?, ?) ON CONFLICT"
                        + " (source_event_id, user_id, channel) DO NOTHING";

        var first = jdbc.update(sql, user, UUID.randomUUID(), event);
        var redelivered = jdbc.update(sql, user, UUID.randomUUID(), event);

        assertThat(first).isEqualTo(1);
        assertThat(redelivered).isZero();
    }

    @Test
    @DisplayName("source_event_id is required")
    void sourceEventId_isRequired() {
        var user = insertUser();

        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notifications (user_id, channel, type,"
                                        + " related_entity_type, related_entity_id) VALUES (?,"
                                        + " 'EMAIL', 'WORKSHOP_CANCELLED', 'WORKSHOP', ?)",
                                user,
                                UUID.randomUUID()),
                NOT_NULL_VIOLATION);
    }

    @Test
    @DisplayName("a new row is PENDING, unclaimed, with no stored content")
    void newRow_defaults() {
        var user = insertUser();
        insertNotification(UUID.randomUUID(), user, "EMAIL");

        var row = jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ?", user);

        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("claimed_at")).isNull();
        assertThat(row.get("title")).isNull();
        assertThat(row.get("body")).isNull();
    }

    @Test
    @DisplayName("an in-app title longer than 200 characters is rejected")
    void title_isBounded() {
        var user = insertUser();

        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notifications (user_id, channel, type,"
                                        + " related_entity_type, related_entity_id,"
                                        + " source_event_id, title) VALUES (?, 'IN_APP',"
                                        + " 'WORKSHOP_CANCELLED', 'WORKSHOP', ?, ?, ?)",
                                user,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "x".repeat(201)),
                VALUE_TOO_LONG);
    }

    @Test
    @DisplayName("the inbox read is served by its own index")
    void inboxIndex_exists() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'notifications'",
                        String.class);

        assertThat(indexes).contains("idx_notifications_inbox");
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, ?)",
                id,
                "n-" + id + "@example.test",
                "Notified Student");
        return id;
    }

    private void insertNotification(UUID event, UUID user, String channel) {
        insertNotification(event, user, channel, UUID.randomUUID());
    }

    private void insertNotification(UUID event, UUID user, String channel, UUID workshop) {
        jdbc.update(
                "INSERT INTO notifications (user_id, channel, type, related_entity_type,"
                        + " related_entity_id, source_event_id) VALUES (?, ?,"
                        + " 'WORKSHOP_RESCHEDULED', 'WORKSHOP', ?, ?)",
                user,
                channel,
                workshop,
                event);
    }
}
