package com.unihub.notification.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.unihub.notification.application.command.DispatchNotificationsCommand;
import com.unihub.notification.application.command.NotificationFact;
import com.unihub.notification.application.port.in.DispatchNotificationsUseCase;
import com.unihub.notification.domain.exception.NotificationsStillPendingException;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The three payload kinds on one queue, across a real retry hop (Phase 4 checklist D34: AC34.1,
 * AC34.3). After a hop the message is delivered with the fixed key {@code
 * unihub.notification.redelivery.requested}, which says nothing about its payload; the AMQP {@code
 * type} (or, for a message published before it existed, {@code x-original-routing-key}) is what
 * still does. The use case is replaced by a probe that fails each message once, so every message is
 * seen twice: first as published, then after the 5-second hop.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class NotificationEventTypeIT extends IntegrationTestSupport {

    private static final String QUEUE = "unihub.notification.queue";
    private static final String CONFIRMED = "unihub.registration.ticket.confirmed";
    private static final String CANCELLED = "unihub.workshop.event.cancelled";
    private static final String RESCHEDULED = "unihub.workshop.event.rescheduled";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @MockBean private DispatchNotificationsUseCase dispatch;

    @Autowired private RabbitTemplate template;
    @Autowired private RabbitAdmin admin;

    /** Every delivery the probe saw, per message-id, in order. */
    private final Map<UUID, List<NotificationFact>> seen = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        admin.initialize();
        purgeAll();
        reset(dispatch);
        seen.clear();
        doAnswer(
                        invocation -> {
                            DispatchNotificationsCommand command = invocation.getArgument(0);
                            var deliveries =
                                    seen.computeIfAbsent(
                                            command.sourceEventId(),
                                            k -> new CopyOnWriteArrayList<>());
                            deliveries.add(command.fact());
                            if (deliveries.size() == 1) {
                                throw new NotificationsStillPendingException(
                                        "fail the first delivery");
                            }
                            return null;
                        })
                .when(dispatch)
                .dispatch(any());
    }

    @AfterEach
    void clean() {
        purgeAll();
    }

    private void purgeAll() {
        admin.purgeQueue(QUEUE);
        admin.purgeQueue(Topology.PARKING_LOT);
        Topology.retryStageQueues(QUEUE).forEach(admin::purgeQueue);
    }

    /** {@code type} may be null: a message published before the property existed. */
    private UUID publish(String routingKey, String type, String body) {
        var id = UUID.randomUUID();
        var builder =
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setMessageId(id.toString());
        if (type != null) {
            builder.setType(type);
        }
        template.send(Topology.EXCHANGE, routingKey, builder.build());
        return id;
    }

    private static final UUID WORKSHOP = UUID.randomUUID();

    private static String confirmedBody() {
        return "{\"registrationId\":\""
                + UUID.randomUUID()
                + "\",\"workshopId\":\""
                + WORKSHOP
                + "\",\"userId\":\""
                + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\","
                + "\"schemaVersion\":1}";
    }

    private static String cancelledBody() {
        return "{\"workshopId\":\""
                + WORKSHOP
                + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\","
                + "\"schemaVersion\":1}";
    }

    private static String rescheduledBody() {
        return "{\"workshopId\":\""
                + WORKSHOP
                + "\",\"previousStartTime\":\"2026-11-01T01:00:00Z\","
                + "\"previousEndTime\":\"2026-11-01T03:00:00Z\",\"newStartTime\":\"2026-11-02T01:00:00Z\","
                + "\"newEndTime\":\"2026-11-02T03:00:00Z\",\"occurredAt\":\"2026-11-01T00:00:00Z\","
                + "\"schemaVersion\":1}";
    }

    private void awaitTwoDeliveries(UUID... ids) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(25))
                .untilAsserted(
                        () -> {
                            for (var id : ids) {
                                assertThat(seen.get(id)).as(id.toString()).hasSize(2);
                            }
                        });
    }

    @Test
    @DisplayName("each kind is the same fact on the first delivery and after a retry hop (AC34.1)")
    void everyKind_survivesARetryHop() {
        var confirmed = publish(CONFIRMED, CONFIRMED, confirmedBody());
        var cancelled = publish(CANCELLED, CANCELLED, cancelledBody());
        var rescheduled = publish(RESCHEDULED, RESCHEDULED, rescheduledBody());

        awaitTwoDeliveries(confirmed, cancelled, rescheduled);

        assertThat(seen.get(confirmed))
                .allSatisfy(
                        f ->
                                assertThat(f)
                                        .isInstanceOf(
                                                NotificationFact.RegistrationConfirmed.class));
        assertThat(seen.get(cancelled))
                .allSatisfy(
                        f -> assertThat(f).isInstanceOf(NotificationFact.WorkshopCancelled.class));
        assertThat(seen.get(rescheduled))
                .allSatisfy(
                        f ->
                                assertThat(f)
                                        .isInstanceOf(NotificationFact.WorkshopRescheduled.class));
        assertThat(seen.get(cancelled).get(1)).isEqualTo(seen.get(cancelled).get(0));
        assertThat(template.receive(Topology.PARKING_LOT, 300)).isNull();
    }

    @Test
    @DisplayName(
            "a message without type is recognised by its routing key, and still after a hop (AC34.3)")
    void messageWithoutType_survivesARetryHop() {
        var cancelled = publish(CANCELLED, null, cancelledBody());
        var rescheduled = publish(RESCHEDULED, null, rescheduledBody());

        awaitTwoDeliveries(cancelled, rescheduled);

        assertThat(seen.get(cancelled))
                .allSatisfy(
                        f -> assertThat(f).isInstanceOf(NotificationFact.WorkshopCancelled.class));
        assertThat(seen.get(rescheduled))
                .allSatisfy(
                        f ->
                                assertThat(f)
                                        .isInstanceOf(NotificationFact.WorkshopRescheduled.class));
        assertThat(template.receive(Topology.PARKING_LOT, 300)).isNull();
    }
}
