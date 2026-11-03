package com.unihub.shared.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only access to {@code audit_events} (database-schema.md §8.3). */
public interface AuditEventStore {

    /** One row to append; the fields mirror {@code audit_events}. */
    record Row(
            UUID sourceMessageId,
            String actorType,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Instant occurredAt,
            Map<String, Object> metadata) {}

    /**
     * Inserts the row unless one with the same {@code sourceMessageId} exists.
     *
     * @return whether a row was written
     */
    boolean appendIfAbsent(Row row);
}
