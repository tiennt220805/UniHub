package com.unihub.shared.infrastructure;

import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What the audit pipeline ITs share (Phase 6 checklist 2.3, 2.6): the real consumer of {@code
 * unihub.audit.queue} running (auto-startup on) against a RabbitMQ of their own. Not the broker
 * every other class shares: a running consumer there would take the audit messages other tests
 * publish (every audited operation publishes one) and write rows for them, and the queue depths
 * other tests count would no longer be theirs alone. Subclasses are {@code @SpringBootTest}s that
 * close their context afterwards ({@code @DirtiesContext}).
 */
public abstract class AuditITSupport extends IntegrationTestSupport {
    protected static final String QUEUE = "unihub.audit.queue";
    protected static final String PARKING_LOT = Topology.PARKING_LOT;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        AuditBroker.register(registry);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected RabbitTemplate rabbit;
    @Autowired protected AmqpAdmin amqpAdmin;

    private final List<UUID> targets = new ArrayList<>();

    @BeforeEach
    void emptyTheAuditBroker() {
        AuditBroker.purgeAll(rabbit, amqpAdmin);
    }

    @AfterEach
    void deleteTheAuditRowsOfTheTest() {
        targets.forEach(id -> jdbc.update("DELETE FROM audit_events WHERE target_id = ?", id));
        targets.clear();
    }

    /** A target id this test owns: its rows are deleted afterwards. */
    protected UUID aTarget() {
        var id = UUID.randomUUID();
        targets.add(id);
        return id;
    }

    /** The body the publisher sends (messaging.md §3), for a system actor (no user row needed). */
    protected static String auditBody(String action, UUID target) {
        return """
               {"schemaVersion":1,"actorType":"SYSTEM","action":"%s","targetType":"WORKSHOP",
                "targetId":"%s","occurredAt":"%s","metadata":{"process":"audit-it"}}"""
                .formatted(action, target, Instant.parse("2026-11-03T08:00:00Z"));
    }

    /** Puts an audit message on the real exchange the way the publisher does. */
    protected void publishAudit(UUID messageId, String body) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (messageId != null) {
            properties.setMessageId(messageId.toString());
        }
        rabbit.send(
                Topology.EXCHANGE,
                Topology.AUDIT_CREATED,
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .andProperties(properties)
                        .build());
    }

    protected List<Map<String, Object>> rowsOf(UUID target) {
        return jdbc.queryForList(
                "SELECT action, actor_type, actor_user_id, target_type, metadata::text AS metadata,"
                        + " source_message_id FROM audit_events WHERE target_id = ? ORDER BY"
                        + " recorded_at",
                target);
    }

    protected int queueDepth(String queue) {
        var info = amqpAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }
}
