package com.unihub.payment.application.event;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * A Deferred Job (backend-coding-standards.md §8.4): Payment tells itself, through the broker, to
 * look at one payment that has stayed {@code PENDING} past the reconciliation threshold (payment.md
 * §6). Module-private, so it lives here and not in {@code api}. The consumer re-reads the payment
 * at the Master and asks the gateway; it acts on what it finds, never on this message, so a
 * duplicate or a late one for a payment already resolved is a no-op.
 */
public record PaymentReconciliationRequested(UUID paymentId, Instant occurredAt)
        implements OutboxMessage {

    public static final String ROUTING_KEY =
            "unihub.payment.reconciliation.requested"; // messaging.md §2
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
