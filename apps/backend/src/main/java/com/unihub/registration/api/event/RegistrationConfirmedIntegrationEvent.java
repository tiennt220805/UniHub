package com.unihub.registration.api.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A registration was confirmed: its student holds a seat. Consumed by Notification, which sends the
 * confirmation. Ids are plain UUIDs so the wire format is flat, not a nested value-object shape.
 */
public record RegistrationConfirmedIntegrationEvent(
        UUID registrationId, UUID workshopId, UUID userId, Instant occurredAt)
        implements OutboxMessage {

    public static final String ROUTING_KEY =
            "unihub.registration.ticket.confirmed"; // messaging.md §2
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }

    @Override
    public String aggregateType() {
        return "REGISTRATION";
    }

    @Override
    public UUID aggregateId() {
        return registrationId;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }
}
