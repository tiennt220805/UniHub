package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-25 end to end (INV-10): a message published inside a business transaction reaches RabbitMQ with
 * no further action, through the real scheduled relay — and one whose transaction rolls back never
 * does. The background trigger is on here, unlike every other integration test.
 */
// The relay trigger is on in this context. Spring caches contexts across test classes, and a live
// relay
// would publish rows other integration tests commit while they assert on them, so the context is
// closed
// when this class ends.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class OutboxToBrokerIT extends IntegrationTestSupport {

    private static final String QUEUE = "unihub.reconciliation.queue";

    record ReconcileRequested(UUID paymentId, Instant occurredAt) implements OutboxMessage {
        @Override
        public String routingKey() {
            return "unihub.payment.reconciliation.requested";
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
            return 1;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> "i".repeat(48));
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @Autowired private EventPublisher publisher;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitTemplate template;
    @Autowired private RabbitAdmin admin;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void declareAndClean() {
        admin.initialize();
        admin.purgeQueue(QUEUE);
        jdbc.update("DELETE FROM event_publications");
    }

    @AfterEach
    void clean() {
        MDC.remove("correlationId");
        admin.purgeQueue(QUEUE);
        jdbc.update("DELETE FROM event_publications");
    }

    @Test
    @DisplayName("a message published in a committed transaction arrives on its queue")
    void committed_reachesTheBroker() throws Exception {
        var paymentId = UUID.randomUUID();
        MDC.put("correlationId", "4bf92f3577b34da6a3ce929d0e0e4736");

        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                publisher.publish(
                                        new ReconcileRequested(
                                                paymentId, Instant.parse("2026-10-16T10:00:00Z"))));

        var message =
                Awaitility.await()
                        .atMost(Duration.ofSeconds(10))
                        .until(() -> template.receive(QUEUE, 200), m -> m != null);
        var body = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(body.get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(message.getMessageProperties().<String>getHeader("correlationId"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT status FROM event_publications",
                                                        String.class))
                                        .isEqualTo("PUBLISHED"));
    }

    @Test
    @DisplayName("a message whose transaction rolls back is never published")
    void rolledBack_neverReachesTheBroker() throws Exception {
        try {
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status -> {
                                publisher.publish(
                                        new ReconcileRequested(UUID.randomUUID(), Instant.now()));
                                throw new IllegalStateException("business failure");
                            });
        } catch (IllegalStateException expected) {
            // the business transaction failed, as intended
        }

        Thread.sleep(1_000); // five relay cycles at 0.2s
        assertThat(template.receive(QUEUE, 200)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publications", Integer.class))
                .isZero();
    }
}
