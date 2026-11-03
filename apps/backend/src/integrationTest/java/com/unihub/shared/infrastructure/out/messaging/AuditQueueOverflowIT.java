package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * AC70.1 (Phase 6 checklist D70, 2.7): a full {@code unihub.audit.queue} loses nothing silently.
 * With the bound at 2 and no consumer, the third entry onwards is refused by the broker ({@code
 * reject-publish-dlx}): the publisher counts each as a {@code nack}, and the refused message lands
 * in the parking lot, which alerts. A message coming back from {@code unihub.audit.retry.*} into
 * the full queue has no publisher to nack it; with plain {@code reject-publish} it would vanish
 * (checked on this broker version, messaging.md §3), and here it goes to the parking lot too. The
 * broker is this class's own: queue arguments cannot change once declared, so the small bound
 * cannot be declared on the broker the other classes share.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class AuditQueueOverflowIT extends IntegrationTestSupport {
    private static final String AUDIT_QUEUE = "unihub.audit.queue";
    private static final String RETRY_QUEUE = "unihub.audit.retry.5s";

    private static final RabbitMQContainer BROKER =
            new RabbitMQContainer("rabbitmq:3.13.7-management");

    static {
        BROKER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("spring.rabbitmq.host", BROKER::getHost);
        registry.add("spring.rabbitmq.port", BROKER::getAmqpPort);
        registry.add("spring.rabbitmq.username", BROKER::getAdminUsername);
        registry.add("spring.rabbitmq.password", BROKER::getAdminPassword);
        registry.add("unihub.audit.queue-max-length", () -> "2");
        registry.add("unihub.messaging.retry-delays", () -> "PT0.3S,PT0.3S,PT0.3S");
        // no listener: nothing may drain the queue
    }

    @Autowired private AuditTrail trail;
    @Autowired private MeterRegistry meters;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin amqpAdmin;

    @BeforeEach
    void declareTheTopology() {
        rabbit.execute(
                channel -> {
                    channel.queueDeclarePassive(Topology.PARKING_LOT);
                    return null;
                });
    }

    private double nacks() {
        return meters.get(RabbitAuditTrail.FAILURE_COUNTER).tag("reason", "nack").counter().count();
    }

    private int depth(String queue) {
        var info = amqpAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    @Test
    @DisplayName(
            "refused publishes are nacked and parked; a retry message returning to the full"
                    + " queue is parked; none is lost")
    void overflow_isCountedOrParked_neverLost() {
        for (int i = 0; i < 5; i++) {
            trail.record(
                    AuditEntry.of(
                            Actor.system("overflow-it"),
                            AuditActions.WORKSHOP_CANCELLED,
                            AuditTargets.WORKSHOP,
                            UUID.randomUUID(),
                            Instant.now()));
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> nacks() == 3);
        await().atMost(Duration.ofSeconds(30)).until(() -> depth(Topology.PARKING_LOT) == 3);
        assertThat(depth(AUDIT_QUEUE)).as("the bound").isEqualTo(2);

        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        rabbit.send(
                "",
                RETRY_QUEUE,
                MessageBuilder.withBody("{}".getBytes()).andProperties(properties).build());

        await().atMost(Duration.ofSeconds(30)).until(() -> depth(Topology.PARKING_LOT) == 4);
        assertThat(depth(AUDIT_QUEUE)).isEqualTo(2);
        assertThat(depth(RETRY_QUEUE)).isZero();
    }
}
