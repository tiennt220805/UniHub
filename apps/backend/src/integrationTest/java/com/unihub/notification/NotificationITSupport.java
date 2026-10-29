package com.unihub.notification;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the Notification ITs that run the real consumer share (Phase 4 checklist 2.8, 3.x, 4.x): the
 * listeners are on, students, workshops and registrations are committed through plain JDBC and the
 * repository, and a test puts the lifecycle messages on the real exchange itself. The outbox relay
 * stays off unless the subclass turns it on. Subclasses close their context afterwards
 * ({@code @DirtiesContext}) because the listeners run.
 *
 * <p>{@code notifications.user_id} is a restricting foreign key, and a delivery still in flight can
 * insert a row after a test has finished, so the clean-up removes the notifications of the users
 * first and a test only ends once its messages are settled.
 */
public abstract class NotificationITSupport extends IntegrationTestSupport {

    protected static final String QUEUE = "unihub.notification.queue";
    protected static final String CONFIRMED = "unihub.registration.ticket.confirmed";
    protected static final String CANCELLED = "unihub.workshop.event.cancelled";
    protected static final String RESCHEDULED = "unihub.workshop.event.rescheduled";

    @DynamicPropertySource
    static void notificationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WorkshopRepository workshops;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected RabbitTemplate rabbit;
    @Autowired protected RabbitAdmin admin;

    protected RegistrationFixtures fixtures;
    private final List<UUID> users = new ArrayList<>();

    @BeforeEach
    void setUpNotifications() {
        admin.initialize();
        purgeNotificationQueues();
        fixtures = new RegistrationFixtures(jdbc);
        users.clear();
    }

    @AfterEach
    void cleanUpNotifications() {
        purgeNotificationQueues();
        users.forEach(user -> jdbc.update("DELETE FROM notifications WHERE user_id = ?", user));
        fixtures.cleanUp();
    }

    protected void purgeNotificationQueues() {
        admin.purgeQueue(QUEUE);
        admin.purgeQueue(Topology.PARKING_LOT);
        Topology.retryStageQueues(QUEUE).forEach(admin::purgeQueue);
    }

    // ---------- rows ----------

    protected UUID student() {
        var id = fixtures.aStudent().value();
        users.add(id);
        return id;
    }

    protected String emailOf(UUID user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
    }

    protected WorkshopId aWorkshop(String title) {
        var workshop = aPublishedWorkshop().withTitle(title).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    /**
     * A free registration in this state (a {@code RESERVED} one gets a hold, as the table needs).
     */
    protected UUID registration(UUID user, WorkshopId workshop, String status) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status,"
                        + " hold_expires_at) VALUES (?, ?, ?, 'FREE', ?, CASE WHEN ? = 'RESERVED'"
                        + " THEN now() + interval '1 hour' END)",
                id,
                workshop.value(),
                user,
                status,
                status);
        return id;
    }

    // ---------- messages, as the relay publishes them ----------

    /** One lifecycle message: the routing key is also the AMQP {@code type} (D34). */
    protected UUID publish(String routingKey, String json) {
        var messageId = UUID.randomUUID();
        rabbit.send(
                Topology.EXCHANGE,
                routingKey,
                MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setMessageId(messageId.toString())
                        .setType(routingKey)
                        .build());
        return messageId;
    }

    protected UUID publishConfirmed(UUID registration, WorkshopId workshop, UUID user) {
        return publish(
                CONFIRMED,
                "{\"registrationId\":\""
                        + registration
                        + "\",\"workshopId\":\""
                        + workshop.value()
                        + "\",\"userId\":\""
                        + user
                        + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\",\"schemaVersion\":1}");
    }

    protected UUID publishCancelled(WorkshopId workshop) {
        return publish(
                CANCELLED,
                "{\"workshopId\":\""
                        + workshop.value()
                        + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\",\"schemaVersion\":1}");
    }

    // ---------- what the consumer left ----------

    protected int notificationRows(UUID user, String channel, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND channel = ? AND status"
                        + " = ?",
                Integer.class,
                user,
                channel,
                status);
    }

    protected int notificationRows(UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, user);
    }

    protected int parkedMessages() {
        return admin.getQueueInfo(Topology.PARKING_LOT).getMessageCount();
    }

    protected int queuedMessages() {
        return admin.getQueueInfo(QUEUE).getMessageCount();
    }
}
