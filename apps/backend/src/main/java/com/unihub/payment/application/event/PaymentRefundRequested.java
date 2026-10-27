package com.unihub.payment.application.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A Deferred Job (backend-coding-standards.md §8.4): Payment tells itself, through the broker, that
 * this payment is owed back (payment.md §9, checklist D26). Written in the same transaction as
 * every move to {@code REFUND_REQUIRED}, so a crash before the gateway is called loses nothing.
 * Module-private, so it lives here and not in {@code api}. Its consumer is the only code that calls
 * the gateway refund; it re-reads the payment at the Master and acts on what it finds, never on
 * this message.
 */
public record PaymentRefundRequested(UUID paymentId, Instant occurredAt) implements OutboxMessage {

    public static final String ROUTING_KEY = "unihub.payment.refund.requested"; // messaging.md §2
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
