package com.unihub.shared.infrastructure.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.event.OutboxMessage;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link EventPublisher} over {@code event_publications} (database-schema.md §8.2). Inserts one
 * {@code PENDING} row in the caller's transaction and never touches RabbitMQ; the relay moves the
 * row onto the broker later.
 *
 * <p>The row id becomes the AMQP {@code message-id}. The correlation id is read from the MDC now,
 * because the relay publishes from another thread outside this request's trace.
 */
@Repository
class OutboxEventPublisher implements EventPublisher {

    static final String CORRELATION_ID_MDC_KEY = "correlationId";
    static final String SCHEMA_VERSION_FIELD = "schemaVersion";

    private static final String INSERT =
            "INSERT INTO event_publications"
                    + " (id, aggregate_type, aggregate_id, event_type, routing_key, payload,"
                    + " correlation_id) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    OutboxEventPublisher(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void publish(OutboxMessage message) {
        Objects.requireNonNull(message, "message");
        jdbc.update(
                INSERT,
                UUID.randomUUID(),
                message.aggregateType(),
                message.aggregateId(),
                message.getClass().getSimpleName(),
                message.routingKey(),
                payloadOf(message),
                MDC.get(CORRELATION_ID_MDC_KEY));
    }

    /**
     * The record components plus {@code schemaVersion}: {@code event_publications} has no column
     * for the version, and a consumer needs it in the body to reject a version it does not know.
     */
    private String payloadOf(OutboxMessage message) {
        try {
            var body = (ObjectNode) json.valueToTree(message);
            body.put(SCHEMA_VERSION_FIELD, message.schemaVersion());
            return json.writeValueAsString(body);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Message " + message.getClass().getName() + " is not serializable", e);
        }
    }
}
