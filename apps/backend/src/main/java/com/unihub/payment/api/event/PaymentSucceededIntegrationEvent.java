package com.unihub.payment.api.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment succeeded. Consumed by Registration, which confirms the registration if it is still
 * {@code RESERVED} (payment.md §9, checklist D2). Ids are plain UUIDs so the wire format is flat.
 */
public record PaymentSucceededIntegrationEvent(
        UUID paymentId, UUID registrationId, Instant occurredAt) implements OutboxMessage {

    public static final String ROUTING_KEY = "unihub.payment.charge.succeeded"; // messaging.md §2
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }

    @Override
    public String aggregateType() {
        return "PAYMENT";
    }

    @Override
    public UUID aggregateId() {
        return paymentId;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }
}
