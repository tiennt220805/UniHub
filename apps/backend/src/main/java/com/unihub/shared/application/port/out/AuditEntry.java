package com.unihub.shared.application.port.out;

import com.unihub.shared.security.Actor;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One audited operation; the fields mirror {@code audit_events} (database-schema.md §8.3). */
public record AuditEntry(
        Actor actor,
        String action,
        String targetType,
        UUID targetId,
        Instant occurredAt,
        Map<String, Object> metadata) {

    public AuditEntry {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        metadata = Map.copyOf(metadata);
    }

    public static AuditEntry of(
            Actor actor, String action, String targetType, UUID targetId, Instant occurredAt) {
        return new AuditEntry(actor, action, targetType, targetId, occurredAt, Map.of());
    }

    public AuditEntry withMetadata(Map<String, Object> metadata) {
        return new AuditEntry(actor, action, targetType, targetId, occurredAt, metadata);
    }
}
