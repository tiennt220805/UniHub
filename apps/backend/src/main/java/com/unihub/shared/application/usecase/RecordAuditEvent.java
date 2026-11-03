package com.unihub.shared.application.usecase;

import com.unihub.shared.application.command.RecordAuditEventCommand;
import com.unihub.shared.application.port.in.RecordAuditEventUseCase;
import com.unihub.shared.application.port.out.AuditEventStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit message's idempotency is the unique {@code source_message_id}: a redelivery, or two
 * deliveries at once, find the row there already and commit a no-op. No state to re-read, no extra
 * table (backend-coding-standards.md §8.5).
 */
@Service
class RecordAuditEvent implements RecordAuditEventUseCase {

    private final AuditEventStore events;

    RecordAuditEvent(AuditEventStore events) {
        this.events = events;
    }

    @Override
    @Transactional
    public void record(RecordAuditEventCommand command) {
        events.appendIfAbsent(
                new AuditEventStore.Row(
                        command.sourceMessageId(),
                        command.actorType().name(),
                        command.actorUserId(),
                        command.action(),
                        command.targetType(),
                        command.targetId(),
                        command.occurredAt(),
                        command.metadata()));
    }
}
