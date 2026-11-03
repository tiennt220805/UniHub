package com.unihub.shared.infrastructure.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.command.RecordAuditEventCommand;
import com.unihub.shared.application.command.RecordAuditEventCommand.ActorType;
import com.unihub.shared.application.port.in.RecordAuditEventUseCase;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The consumer of {@code unihub.audit.queue} (messaging.md §3, Phase 6 checklist D69). Audit Event
 * is a Technical Pattern Entity owned by {@code shared} (backend-coding-standards.md §13), so its
 * consumer is here and not in a business module. Maps and delegates; never catches, so a database
 * that cannot be written keeps the message in the audit retry chain.
 *
 * <p>The AMQP {@code message-id} is the idempotency key. A message without one would slip past the
 * unique index (a unique index allows any number of {@code NULL}s) and be written again on every
 * redelivery, so it is a permanent failure and goes to the parking lot.
 */
@Component
class AuditEventListener {
    static final String QUEUE = "unihub.audit.queue";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The fields of the audit message (messaging.md §3). */
    record AuditCreatedMessage(
            Integer schemaVersion,
            String actorType,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Instant occurredAt,
            Map<String, Object> metadata) {}

    private final RecordAuditEventUseCase recordAuditEvent;
    private final ObjectMapper json;

    AuditEventListener(RecordAuditEventUseCase recordAuditEvent, ObjectMapper json) {
        this.recordAuditEvent = recordAuditEvent;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE)
    void onAuditCreated(Message message) {
        var sourceMessageId = messageId(message.getMessageProperties().getMessageId());
        var audit = read(message);
        if (audit.schemaVersion() == null || audit.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException(
                    "unsupported schemaVersion " + audit.schemaVersion());
        }
        recordAuditEvent.record(commandOf(sourceMessageId, audit));
    }

    private static RecordAuditEventCommand commandOf(
            UUID sourceMessageId, AuditCreatedMessage audit) {
        try {
            return new RecordAuditEventCommand(
                    sourceMessageId,
                    actorType(audit.actorType()),
                    audit.actorUserId(),
                    audit.action(),
                    audit.targetType(),
                    audit.targetId(),
                    audit.occurredAt(),
                    audit.metadata());
        } catch (NullPointerException | IllegalArgumentException e) {
            // The command refuses a message that lacks a field the table requires: retrying the
            // same bytes cannot change that.
            throw new NonRetryableMessageException(
                    "incomplete audit message: " + e.getMessage(), e);
        }
    }

    private static UUID messageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new NonRetryableMessageException("audit message without a message-id");
        }
        try {
            return UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableMessageException("message-id is not a UUID: " + messageId, e);
        }
    }

    private static ActorType actorType(String actorType) {
        try {
            return ActorType.valueOf(String.valueOf(actorType));
        } catch (IllegalArgumentException e) {
            throw new NonRetryableMessageException("unknown actorType " + actorType, e);
        }
    }

    private AuditCreatedMessage read(Message message) {
        try {
            return json.readValue(message.getBody(), AuditCreatedMessage.class);
        } catch (IOException e) {
            throw new NonRetryableMessageException("the body is not an audit message", e);
        }
    }
}
