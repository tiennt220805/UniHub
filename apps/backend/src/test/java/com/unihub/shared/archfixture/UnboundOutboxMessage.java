package com.unihub.shared.archfixture;

import com.unihub.shared.event.OutboxMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * An outbox message whose routing key no queue is bound to: what {@code OutboxRoutingCoverageTest}
 * must turn red on (Phase 4 D42, AC42.1). The relay would write it, RabbitMQ would acknowledge and
 * drop it, and the row would be {@code PUBLISHED} with no one ever having received it. Test sources
 * are not imported as production classes, so it does not count toward the eight.
 */
public record UnboundOutboxMessage(UUID id, Instant occurredAt) implements OutboxMessage {

    public static final String ROUTING_KEY = "unihub.fixture.unbound.event";

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }

    @Override
    public String aggregateType() {
        return "FIXTURE";
    }

    @Override
    public UUID aggregateId() {
        return id;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
