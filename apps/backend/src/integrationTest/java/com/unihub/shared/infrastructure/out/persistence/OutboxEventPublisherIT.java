package com.unihub.shared.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-25's write half (INV-10, backend-coding-standards.md §8.2–§8.3): the outbox row exists exactly
 * when the caller's transaction commits, and carries what the relay needs to publish it.
 */
@SpringBootTest
class OutboxEventPublisherIT extends IntegrationTestSupport {

    /** Shaped like a module's integration event. */
    record SampleConfirmed(UUID registrationId, Instant occurredAt) implements OutboxMessage {
        static final String ROUTING_KEY = "unihub.registration.ticket.confirmed";

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
            return 3;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> "i".repeat(48));
    }

    @Autowired private EventPublisher publisher;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private final UUID registrationId = UUID.randomUUID();
    private final SampleConfirmed message =
            new SampleConfirmed(registrationId, Instant.parse("2026-10-16T10:00:00Z"));

    @AfterEach
    void cleanUp() {
        MDC.remove("correlationId");
        jdbc.update("DELETE FROM event_publications");
    }

    private void publishInTransaction(OutboxMessage toPublish) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> publisher.publish(toPublish));
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList(
                "SELECT id, aggregate_type, aggregate_id, event_type, routing_key,"
                        + " payload::text AS payload, correlation_id, status, published_at"
                        + " FROM event_publications");
    }

    @Test
    @DisplayName(
            "publish in a committed transaction leaves one PENDING row addressed by the message")
    void publish_committed_leavesOnePendingRow() {
        publishInTransaction(message);

        assertThat(rows()).hasSize(1);
        var row = rows().getFirst();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("aggregate_type")).isEqualTo("REGISTRATION");
        assertThat(row.get("aggregate_id")).isEqualTo(registrationId);
        assertThat(row.get("event_type")).isEqualTo("SampleConfirmed");
        assertThat(row.get("routing_key")).isEqualTo("unihub.registration.ticket.confirmed");
    }

    @Test
    @DisplayName(
            "the payload is the record's business content plus schemaVersion, and nothing else")
    void publish_payload_isBusinessContentPlusSchemaVersion() throws Exception {
        publishInTransaction(message);

        JsonNode payload = json.readTree((String) rows().getFirst().get("payload"));
        assertThat(payload.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("registrationId", "occurredAt", "schemaVersion");
        assertThat(payload.get("registrationId").asText()).isEqualTo(registrationId.toString());
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-10-16T10:00:00Z");
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("the correlation id in the MDC is captured into the row")
    void publish_capturesCorrelationIdFromMdc() {
        MDC.put("correlationId", "4bf92f3577b34da6a3ce929d0e0e4736");

        publishInTransaction(message);

        assertThat(rows().getFirst().get("correlation_id"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("without a traced context the correlation id is NULL")
    void publish_withoutMdc_hasNullCorrelationId() {
        publishInTransaction(message);

        assertThat(rows().getFirst().get("correlation_id")).isNull();
    }

    @Test
    @DisplayName("rolling the caller's transaction back leaves no row")
    void publish_rolledBack_leavesNoRow() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                status -> {
                                                    publisher.publish(message);
                                                    throw new IllegalStateException("boom");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("publish without a transaction is refused (MANDATORY)")
    void publish_withoutTransaction_isRefused() {
        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("two messages are two rows with distinct ids")
    void publish_twice_isTwoRows() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            publisher.publish(message);
                            publisher.publish(message);
                        });

        assertThat(rows()).hasSize(2);
        assertThat(rows().stream().map(r -> r.get("id")).distinct()).hasSize(2);
    }
}
