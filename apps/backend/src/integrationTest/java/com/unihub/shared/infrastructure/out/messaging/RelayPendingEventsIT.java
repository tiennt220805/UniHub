package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.application.port.in.RelayPendingEventsUseCase;
import com.unihub.shared.infrastructure.ReplicaIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-25's relay half (INV-10, messaging.md §5) on a real Postgres Master and streaming Replica and a
 * real RabbitMQ: what reaches the broker, what is left {@code PENDING}, and which database the
 * relay reads. The background trigger is off; the use case is called directly.
 */
@SpringBootTest
class RelayPendingEventsIT extends ReplicaIntegrationTestSupport {

    private static final String RECONCILIATION_KEY = "unihub.payment.reconciliation.requested";
    private static final String RECONCILIATION_QUEUE = "unihub.reconciliation.queue";
    private static final String CORRELATION = "4bf92f3577b34da6a3ce929d0e0e4736";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "unihub.datasource.replica.url", ReplicaIntegrationTestSupport::replicaJdbcUrl);
        registry.add("unihub.security.jwt.secret", () -> "i".repeat(48));
    }

    @Autowired private RelayPendingEventsUseCase relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitTemplate template;
    @Autowired private RabbitAdmin admin;

    @BeforeEach
    void declareAndClean() {
        admin.initialize();
        admin.purgeQueue(RECONCILIATION_QUEUE);
        jdbc.update("DELETE FROM event_publications");
    }

    @AfterEach
    void clean() {
        admin.purgeQueue(RECONCILIATION_QUEUE);
        jdbc.update("DELETE FROM event_publications");
    }

    private UUID insert(
            String routingKey, String payload, String correlationId, Instant createdAt) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO event_publications (id, aggregate_type, aggregate_id, event_type,"
                        + " routing_key, payload, correlation_id, created_at)"
                        + " VALUES (?, 'PAYMENT', ?, 'Sample', ?, ?::jsonb, ?, ?)",
                id,
                UUID.randomUUID(),
                routingKey,
                payload,
                correlationId,
                Timestamp.from(createdAt));
        return id;
    }

    private UUID insert(String routingKey, String payload) {
        return insert(routingKey, payload, null, Instant.now());
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM event_publications WHERE id = ?", String.class, id);
    }

    private Message receive() {
        return template.receive(RECONCILIATION_QUEUE, 5_000);
    }

    @Test
    @DisplayName(
            "a pending row reaches its queue with the row id as message-id, and becomes PUBLISHED")
    void publishesRowAndMarksIt() {
        var id = insert(RECONCILIATION_KEY, "{\"paymentId\":\"p-1\",\"schemaVersion\":1}");

        int published = relay.relay(200);

        assertThat(published).isEqualTo(1);
        var message = receive();
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo(RECONCILIATION_KEY);
        assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_at IS NOT NULL FROM event_publications"
                                        + " WHERE id = ?",
                                Boolean.class,
                                id))
                .isTrue();
    }

    @Test
    @DisplayName("the body is the stored JSON itself, not a JSON string containing it")
    void bodyIsNotEncodedTwice() {
        insert(RECONCILIATION_KEY, "{\"paymentId\":\"p-1\",\"schemaVersion\":1}");

        relay.relay(200);

        var body = new String(receive().getBody(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("{").doesNotStartWith("\"");
        assertThat(body).contains("\"paymentId\"").contains("\"p-1\"");
    }

    @Test
    @DisplayName("the stored correlation id travels as the correlationId header")
    void correlationIdIsRestoredIntoTheHeader() {
        insert(RECONCILIATION_KEY, "{}", CORRELATION, Instant.now());

        relay.relay(200);

        assertThat(receive().getMessageProperties().<String>getHeader("correlationId"))
                .isEqualTo(CORRELATION);
    }

    @Test
    @DisplayName("a row with no correlation id publishes without the header")
    void noCorrelationId_noHeader() {
        insert(RECONCILIATION_KEY, "{}");

        relay.relay(200);

        assertThat(receive().getMessageProperties().<String>getHeader("correlationId")).isNull();
    }

    @Test
    @DisplayName("a published row is not published again by the next cycle")
    void publishedRow_isNotRepublished() {
        insert(RECONCILIATION_KEY, "{}");

        assertThat(relay.relay(200)).isEqualTo(1);
        assertThat(relay.relay(200)).isZero();

        assertThat(receive()).isNotNull();
        assertThat(template.receive(RECONCILIATION_QUEUE, 300)).isNull();
    }

    @Test
    @DisplayName("an unroutable row stays PENDING while the rows behind it still publish")
    void unroutableRow_doesNotBlockTheRowsBehindIt() {
        var oldest = Instant.now().minusSeconds(30);
        var unroutable = insert("unihub.nobody.bound.here", "{}", null, oldest);
        var routable = insert(RECONCILIATION_KEY, "{}", null, oldest.plusSeconds(1));

        int published = relay.relay(200);

        assertThat(published).isEqualTo(1);
        assertThat(status(unroutable)).isEqualTo("PENDING");
        assertThat(status(routable)).isEqualTo("PUBLISHED");
        assertThat(receive().getMessageProperties().getMessageId()).isEqualTo(routable.toString());
    }

    @Test
    @DisplayName("an unroutable row is retried on every cycle until a binding exists")
    void unroutableRow_isRetriedEachCycle() {
        var unroutable = insert("unihub.nobody.bound.here", "{}");

        assertThat(relay.relay(200)).isZero();
        assertThat(relay.relay(200)).isZero();

        assertThat(status(unroutable)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("rows are published oldest first, up to the batch size")
    void oldestFirst_boundedByBatchSize() {
        var now = Instant.now();
        var third = insert(RECONCILIATION_KEY, "{}", null, now.minusSeconds(10));
        var first = insert(RECONCILIATION_KEY, "{}", null, now.minusSeconds(30));
        var second = insert(RECONCILIATION_KEY, "{}", null, now.minusSeconds(20));

        int published = relay.relay(2);

        assertThat(published).isEqualTo(2);
        assertThat(status(first)).isEqualTo("PUBLISHED");
        assertThat(status(second)).isEqualTo("PUBLISHED");
        assertThat(status(third)).isEqualTo("PENDING");
        assertThat(receive().getMessageProperties().getMessageId()).isEqualTo(first.toString());
        assertThat(receive().getMessageProperties().getMessageId()).isEqualTo(second.toString());
    }

    @Test
    @DisplayName(
            "the relay reads the Master: a row the paused Replica has not seen still publishes")
    void readsTheMaster_notTheReplica() throws Exception {
        withReplayPaused(
                () -> {
                    var id = insert(RECONCILIATION_KEY, "{}");

                    // The control: through the Replica the row does not exist yet, so a relay that
                    // read it there would find nothing to publish.
                    assertThat(rowsVisibleOnReplica(id)).isZero();

                    assertThat(relay.relay(200)).isEqualTo(1);
                    assertThat(status(id)).isEqualTo("PUBLISHED");
                });
    }

    private long rowsVisibleOnReplica(UUID id) throws Exception {
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                replicaJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement =
                        connection.prepareStatement(
                                "SELECT count(*) FROM event_publications WHERE id = ?")) {
            statement.setObject(1, id);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("nothing pending publishes nothing")
    void nothingPending() {
        assertThat(relay.relay(200)).isZero();
    }
}
