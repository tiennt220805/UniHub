package com.unihub.shared.application.port.in;

import com.unihub.shared.application.command.RecordAuditEventCommand;

/**
 * The consumer of {@code unihub.audit.queue} (messaging.md §3): appends one {@code audit_events}
 * row.
 */
public interface RecordAuditEventUseCase {

    /**
     * Appends the row unless one with the same {@code sourceMessageId} exists already, so handling
     * the same message again, or twice at once, leaves exactly one row (INV-11).
     */
    void record(RecordAuditEventCommand command);
}
