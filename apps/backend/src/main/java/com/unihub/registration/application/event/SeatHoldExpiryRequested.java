package com.unihub.registration.application.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A Deferred Job (backend-coding-standards.md §8.4): Registration tells itself, through the delay
 * queue, to look at this hold once its window has passed. Module-private, so it lives here and not
 * in {@code api}. The delay comes from the queue's {@code x-message-ttl}, not from a field; the
 * consumer (Phase 3) re-reads {@code status} and {@code hold_expires_at} from the Master and acts
 * on what it finds, never on this message.
 */
public record SeatHoldExpiryRequested(
        UUID registrationId, UUID workshopId, Instant holdExpiresAt, Instant occurredAt)
        implements OutboxMessage {

    public static final String ROUTING_KEY = "unihub.registration.hold.reserved"; // messaging.md §2
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
