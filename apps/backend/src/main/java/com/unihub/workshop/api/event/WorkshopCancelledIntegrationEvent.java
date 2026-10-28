package com.unihub.workshop.api.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A workshop was cancelled. Consumed by Notification (tells registered students) and, later, by
 * Registration (cancels the registrations and releases the seats; not scheduled yet,
 * backend-coding-standards.md §27 #8). Notification reads the recipients when it consumes this
 * event, so registrations cancelled because of it must still count as recipients, or those students
 * are never told (registration.md, "Internal read for Notification"). Ids are plain UUIDs so the
 * wire format is flat, not a nested value-object shape.
 */
public record WorkshopCancelledIntegrationEvent(UUID workshopId, Instant occurredAt)
        implements OutboxMessage {

    public static final String ROUTING_KEY = "unihub.workshop.event.cancelled"; // messaging.md §2
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }

    @Override
    public String aggregateType() {
        return "WORKSHOP";
    }

    @Override
    public UUID aggregateId() {
        return workshopId;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }
}
