package com.unihub.workshop.api.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A workshop moved to new times. Carries the old and the new times so a notification can say what
 * changed. Ids are plain UUIDs so the wire format is flat.
 */
public record WorkshopRescheduledIntegrationEvent(
        UUID workshopId,
        Instant previousStartTime,
        Instant previousEndTime,
        Instant newStartTime,
        Instant newEndTime,
        Instant occurredAt)
        implements OutboxMessage {

    public static final String ROUTING_KEY = "unihub.workshop.event.rescheduled"; // messaging.md §2
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
