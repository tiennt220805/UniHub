package com.unihub.registration.api.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment succeeded for a registration that can no longer be confirmed (its hold ended, or it was
 * cancelled): Payment takes it from here, moving the payment to {@code REFUND_REQUIRED} (payment.md
 * §9, checklist D2). The registration is not changed and the seat is never re-granted.
 *
 * <p>A redelivery of the success makes Registration emit this again, with a new event id; Payment
 * deduplicates by its own conditional transition, per payment.
 */
public record RegistrationConfirmationRejectedIntegrationEvent(
        UUID paymentId, UUID registrationId, String reason, Instant occurredAt)
        implements OutboxMessage {

    public static final String ROUTING_KEY =
            "unihub.registration.confirmation.rejected"; // messaging.md §2
    public static final int SCHEMA_VERSION = 1;
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    public static final String REGISTRATION_CANCELLED = "REGISTRATION_CANCELLED";

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
