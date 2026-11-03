package com.unihub.shared.application.command;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One audit message to persist (backend-coding-standards.md §12). {@code sourceMessageId} is the
 * AMQP {@code message-id} the publisher gave the entry; it is what makes a redelivery a no-op
 * (database-schema.md §8.3).
 */
public record RecordAuditEventCommand(
        UUID sourceMessageId,
        ActorType actorType,
        UUID actorUserId,
        String action,
        String targetType,
        UUID targetId,
        Instant occurredAt,
        Map<String, Object> metadata) {

    /** Mirrors {@code audit_events.actor_type}. */
    public enum ActorType {
        USER,
        SYSTEM
    }

    public RecordAuditEventCommand {
        Objects.requireNonNull(sourceMessageId, "sourceMessageId");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if ((actorType == ActorType.USER) != (actorUserId != null)) {
            throw new IllegalArgumentException(
                    "a USER actor has a user id and a SYSTEM actor has none");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
