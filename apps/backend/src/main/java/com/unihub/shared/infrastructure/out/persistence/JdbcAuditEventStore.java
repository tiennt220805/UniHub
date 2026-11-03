package com.unihub.shared.infrastructure.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.AuditEventStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link AuditEventStore} over {@code audit_events}. The unique index on {@code source_message_id}
 * (migration {@code V8}) is the enforced backstop of the consumer's idempotency: the second of two
 * concurrent inserts of the same message waits for the first and then does nothing.
 */
@Repository
class JdbcAuditEventStore implements AuditEventStore {

    private static final String INSERT =
            "INSERT INTO audit_events (actor_type, actor_user_id, action, target_type, target_id,"
                    + " metadata, occurred_at, source_message_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
                    + " ON CONFLICT (source_message_id) DO NOTHING";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcAuditEventStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean appendIfAbsent(Row row) {
        return jdbc.update(
                        INSERT,
                        row.actorType(),
                        row.actorUserId(),
                        row.action(),
                        row.targetType(),
                        row.targetId(),
                        metadataJson(row),
                        OffsetDateTime.ofInstant(row.occurredAt(), ZoneOffset.UTC),
                        row.sourceMessageId())
                == 1;
    }

    private String metadataJson(Row row) {
        try {
            return json.writeValueAsString(row.metadata());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit metadata is not serializable", e);
        }
    }
}
