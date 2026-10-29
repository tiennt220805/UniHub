package com.unihub.notification;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.UniHubApplication;
import com.unihub.notification.application.command.DispatchNotificationsCommand;
import com.unihub.notification.application.port.in.DispatchNotificationsUseCase;
import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.application.usecase.NotificationDispatcher;
import com.unihub.notification.domain.exception.NotificationsStillPendingException;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.infrastructure.ControllableSmtp;
import com.unihub.shared.infrastructure.ControllableSmtp.Mode;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.Mailpit;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * INV-11 for the Notification consumer (Phase 4 checklist 6.2, D33, D41: AC33.1 … AC33.5, M-40):
 * the same lifecycle message delivered again and again, in sequence and at the same moment, and
 * what is counted is what the world saw: rows in {@code notifications}, mail in Mailpit, and calls
 * to the channels.
 *
 * <p>Two application contexts in this JVM, built as the multi-instance harness builds its own
 * (separate Spring contexts on the singleton containers, each with a real listener), each with a
 * listener container of <b>one</b> consumer, so that two deliveries of one message are always on
 * different instances and never on two threads of one. The harness class itself is specific to
 * locks, so the contexts are built here. The probes (on the use case and on the channels) are
 * registered programmatically, never as beans: test sources under {@code com.unihub} are
 * component-scanned, and the Boot test exclude filter that keeps test configuration out of a
 * {@code @SpringBootTest} context is registered here by hand for the same reason.
 *
 * <p>The send lease is a few seconds: longer than the slowest send here (the provider is slow, not
 * down) and shorter than the 5-second first retry hop, so a claim left by a crashed holder has
 * expired by the time the hop delivers.
 */
class NotificationRedeliveryIT extends IntegrationTestSupport {

    private static final Duration LONG_ENOUGH = Duration.ofSeconds(60);
    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final String QUEUE = "unihub.notification.queue";
    private static final String CONFIRMED = "unihub.registration.ticket.confirmed";

    private static ControllableSmtp smtp;
    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();

    /** One run of the use case, as the probe saw it. */
    record Delivery(
            int instance, long startNanos, long endNanos, UUID eventId, Throwable failure) {}

    /** One call of a real channel. */
    record ChannelCall(
            int instance, String channel, NotificationDeliveryResult result, UUID user) {}

    private static final List<Delivery> DELIVERIES = new CopyOnWriteArrayList<>();
    private static final List<ChannelCall> CHANNEL_CALLS = new CopyOnWriteArrayList<>();

    private JdbcTemplate jdbc;
    private RabbitTemplate rabbit;
    private RabbitAdmin admin;
    private RegistrationFixtures fixtures;
    private final List<UUID> users = new ArrayList<>();

    @BeforeAll
    static void startTwoInstances() {
        smtp = ControllableSmtp.inFrontOfMailpit();
        for (int instance = 0; instance < 2; instance++) {
            CONTEXTS.add(startInstance(instance));
        }
    }

    @AfterAll
    static void stopTwoInstances() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
        smtp.close();
    }

    private static ConfigurableApplicationContext startInstance(int instance) {
        return new SpringApplicationBuilder(UniHubApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(
                        context -> {
                            var generic = (GenericApplicationContext) context;
                            generic.registerBeanDefinition(
                                    "testTypeExcludeFilter",
                                    new RootBeanDefinition(testTypeExcludeFilter()));
                            generic.getBeanFactory().addBeanPostProcessor(new Probes(instance));
                        })
                // command-line arguments, not builder properties: the system properties of
                // IntegrationTestSupport (listeners off, the shared SMTP) outrank the latter
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--unihub.security.jwt.secret=" + TestJwts.SECRET,
                        "--server.port=0",
                        "--spring.rabbitmq.listener.simple.auto-startup=true",
                        // one consumer per instance: two deliveries are two instances
                        "--unihub.notification.listener.concurrency=1",
                        "--unihub.notification.claim-lease=" + LEASE,
                        "--unihub.notification.smtp.host=" + smtp.host(),
                        "--unihub.notification.smtp.port=" + smtp.port());
    }

    private static Class<?> testTypeExcludeFilter() {
        try {
            return Class.forName(
                    "org.springframework.boot.test.context.filter.TestTypeExcludeFilter");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records every run of the use case and every call of a channel of one instance. */
    private static final class Probes implements BeanPostProcessor {
        private final int instance;

        private Probes(int instance) {
            this.instance = instance;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DispatchNotificationsUseCase real) {
                return Proxy.newProxyInstance(
                        DispatchNotificationsUseCase.class.getClassLoader(),
                        new Class<?>[] {DispatchNotificationsUseCase.class},
                        (proxy, method, args) -> {
                            var start = System.nanoTime();
                            Throwable failure = null;
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                failure = e.getTargetException();
                                throw failure;
                            } finally {
                                var command = (DispatchNotificationsCommand) args[0];
                                DELIVERIES.add(
                                        new Delivery(
                                                instance,
                                                start,
                                                System.nanoTime(),
                                                command.sourceEventId(),
                                                failure));
                            }
                        });
            }
            if (bean instanceof NotificationChannel real) {
                return new NotificationChannel() {
                    @Override
                    public String channelCode() {
                        return real.channelCode();
                    }

                    @Override
                    public NotificationDeliveryResult send(NotificationDispatch dispatch) {
                        var result = real.send(dispatch);
                        CHANNEL_CALLS.add(
                                new ChannelCall(
                                        instance, channelCode(), result, dispatch.userId()));
                        return result;
                    }
                };
            }
            return bean;
        }
    }

    @BeforeEach
    void setUp() {
        var context = CONTEXTS.getFirst();
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        rabbit = context.getBean(RabbitTemplate.class);
        admin = context.getBean(RabbitAdmin.class);
        fixtures = new RegistrationFixtures(jdbc);
        users.clear();
        DELIVERIES.clear();
        CHANNEL_CALLS.clear();
        smtp.mode(Mode.FORWARD);
        purge();
    }

    @AfterEach
    void cleanUp() {
        purge();
        users.forEach(user -> jdbc.update("DELETE FROM notifications WHERE user_id = ?", user));
        fixtures.cleanUp();
    }

    private void purge() {
        admin.purgeQueue(QUEUE);
        admin.purgeQueue(Topology.PARKING_LOT);
        Topology.retryStageQueues(QUEUE).forEach(admin::purgeQueue);
    }

    // ---------- fixtures ----------

    private record Registered(UUID user, UUID workshop, UUID registration, String token) {}

    private Registered aConfirmedRegistration() {
        var token = "redeliver" + UUID.randomUUID().toString().substring(0, 8);
        var workshop = aPublishedWorkshop().withTitle("Redelivery " + token).build();
        WorkshopRows.insert(jdbc, workshop);
        fixtures.track(workshop.id());
        var user = fixtures.aStudent().value();
        users.add(user);
        var registration = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status) VALUES"
                        + " (?, ?, ?, 'FREE', 'CONFIRMED')",
                registration,
                workshop.id().value(),
                user);
        return new Registered(user, workshop.id().value(), registration, token);
    }

    private Message confirmation(Registered r, UUID messageId) {
        var body =
                "{\"registrationId\":\""
                        + r.registration()
                        + "\",\"workshopId\":\""
                        + r.workshop()
                        + "\",\"userId\":\""
                        + r.user()
                        + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\",\"schemaVersion\":1}";
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(messageId.toString())
                .setType(CONFIRMED)
                .build();
    }

    private void publish(Message message) {
        rabbit.send(Topology.EXCHANGE, CONFIRMED, message);
    }

    private int rows(UUID user, String channel, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND channel = ? AND status"
                        + " = ?",
                Integer.class,
                user,
                channel,
                status);
    }

    private int rows(UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, user);
    }

    private long calls(String channel) {
        return CHANNEL_CALLS.stream().filter(c -> c.channel().equals(channel)).count();
    }

    private void awaitDeliveries(int count) {
        Awaitility.await().atMost(LONG_ENOUGH).until(() -> DELIVERIES.size() >= count);
    }

    private String emailOf(UUID user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
    }

    // ---------- the tests ----------

    @Test
    @DisplayName(
            "the harness is what it claims: two instances, one consumer each, two real channels")
    void harnessIsWhatItClaims() {
        assertThat(admin.getQueueInfo(QUEUE).getConsumerCount())
                .as("one consumer on each of the two instances, and no third")
                .isEqualTo(2);
        for (var context : CONTEXTS) {
            assertThat(context.getBean(NotificationDispatcher.class).channelCodes())
                    .as("no test configuration was scanned into the instance")
                    .containsExactlyInAnyOrder("EMAIL", "IN_APP");
        }
    }

    @Test
    @DisplayName(
            "AC33.1, AC33.2: the same message delivered three times in sequence leaves one row per"
                    + " channel, one email, and no provider call after SENT")
    void sequentialRedelivery_sendsOnce() {
        var r = aConfirmedRegistration();
        var messageId = UUID.randomUUID();

        for (int delivery = 1; delivery <= 3; delivery++) {
            publish(confirmation(r, messageId));
            awaitDeliveries(delivery);
        }

        assertThat(rows(r.user())).as("0 duplicate rows").isEqualTo(2);
        assertThat(rows(r.user(), "EMAIL", "SENT")).isEqualTo(1);
        assertThat(rows(r.user(), "IN_APP", "SENT")).isEqualTo(1);
        assertThat(Mailpit.recipientsOfSubject(r.token())).containsExactly(emailOf(r.user()));
        assertThat(calls("EMAIL")).as("no provider call after SENT").isEqualTo(1);
        assertThat(calls("IN_APP")).isEqualTo(1);
        assertThat(DELIVERIES).hasSize(3).allSatisfy(d -> assertThat(d.failure()).isNull());
        assertThat(admin.getQueueInfo(Topology.PARKING_LOT).getMessageCount()).isZero();
    }

    @Test
    @DisplayName("AC33.2: a row that ended FAILED is never sent again by a redelivery")
    void failedRow_isNeverRetried() {
        var r = aConfirmedRegistration();
        // an address the channel refuses without asking the provider: REJECTED, row FAILED
        jdbc.update("UPDATE users SET email = 'not an address' WHERE id = ?", r.user());
        var messageId = UUID.randomUUID();

        for (int delivery = 1; delivery <= 3; delivery++) {
            publish(confirmation(r, messageId));
            awaitDeliveries(delivery);
        }

        assertThat(rows(r.user(), "EMAIL", "FAILED")).isEqualTo(1);
        assertThat(rows(r.user(), "IN_APP", "SENT")).isEqualTo(1);
        assertThat(calls("EMAIL")).as("one attempt, no more after FAILED").isEqualTo(1);
        assertThat(DELIVERIES).hasSize(3).allSatisfy(d -> assertThat(d.failure()).isNull());
    }

    @Test
    @DisplayName(
            "AC33.4: the same message on two instances at the same moment sends one email; the"
                    + " delivery that finds the row claimed does not ack, it goes a hop and finds it SENT")
    void concurrentRedelivery_sendsOnce() {
        var r = aConfirmedRegistration();
        smtp.mode(
                Mode.SLOW); // the send takes long enough for the other delivery to find it claimed
        smtp.slowBy(Duration.ofMillis(800));
        var messageId = UUID.randomUUID();

        publish(confirmation(r, messageId));
        publish(confirmation(r, messageId));

        // two deliveries now, and the one that was turned away comes back after the 5s hop
        awaitDeliveries(3);
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .until(
                        () ->
                                rows(r.user(), "EMAIL", "SENT") == 1
                                        && rows(r.user(), "IN_APP", "SENT") == 1);

        var firstTwo = DELIVERIES.subList(0, 2);
        assertThat(firstTwo)
                .as("the two deliveries were on different instances")
                .extracting(Delivery::instance)
                .containsExactlyInAnyOrder(0, 1);
        assertThat(
                        Math.max(firstTwo.get(0).startNanos(), firstTwo.get(1).startNanos())
                                < Math.min(firstTwo.get(0).endNanos(), firstTwo.get(1).endNanos()))
                .as("their processing intervals overlapped")
                .isTrue();
        assertThat(DELIVERIES.stream().map(Delivery::failure).filter(f -> f != null))
                .as("exactly one delivery was turned away, and not acked")
                .hasSize(1)
                .allSatisfy(
                        f -> assertThat(f).isInstanceOf(NotificationsStillPendingException.class));
        assertThat(DELIVERIES.getLast().failure())
                .as("the hop found everything SENT and acked")
                .isNull();
        assertThat(rows(r.user())).isEqualTo(2);
        assertThat(Mailpit.recipientsOfSubject(r.token())).containsExactly(emailOf(r.user()));
        assertThat(calls("EMAIL")).isEqualTo(1);
        assertThat(calls("IN_APP")).isEqualTo(1);
        assertThat(admin.getQueueInfo(Topology.PARKING_LOT).getMessageCount()).isZero();
    }

    @Test
    @DisplayName(
            "AC33.5: a holder that claimed the rows and died leaves them PENDING; once the lease is"
                    + " out, a retry hop sends them, the message is not parked")
    void crashedHolder_isRecoveredByTheHopAfterTheLease() {
        var r = aConfirmedRegistration();
        var messageId = UUID.randomUUID();
        insertClaimedRows(r, messageId, "now()");

        publish(confirmation(r, messageId));

        awaitDeliveries(2);
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .until(
                        () ->
                                rows(r.user(), "EMAIL", "SENT") == 1
                                        && rows(r.user(), "IN_APP", "SENT") == 1);
        assertThat(DELIVERIES.getFirst().failure())
                .as("the first delivery met a live claim and was not acked")
                .isInstanceOf(NotificationsStillPendingException.class);
        assertThat(DELIVERIES.getLast().failure()).isNull();
        assertThat(Mailpit.recipientsOfSubject(r.token())).containsExactly(emailOf(r.user()));
        assertThat(calls("EMAIL")).isEqualTo(1);
        assertThat(admin.getQueueInfo(Topology.PARKING_LOT).getMessageCount()).isZero();
    }

    @Test
    @DisplayName(
            "AC33.3: a PENDING row whose lease has run out is sent by the redelivery, exactly once"
                    + " (the accepted at-least-once window)")
    void expiredClaim_isSentOnceByTheRedelivery() {
        var r = aConfirmedRegistration();
        var messageId = UUID.randomUUID();
        insertClaimedRows(r, messageId, "now() - interval '1 hour'");

        publish(confirmation(r, messageId));

        awaitDeliveries(1);
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .until(
                        () ->
                                rows(r.user(), "EMAIL", "SENT") == 1
                                        && rows(r.user(), "IN_APP", "SENT") == 1);
        assertThat(DELIVERIES).as("sent by the first redelivery, no hop needed").hasSize(1);
        assertThat(Mailpit.recipientsOfSubject(r.token())).containsExactly(emailOf(r.user()));
        assertThat(calls("EMAIL")).isEqualTo(1);
        assertThat(rows(r.user())).isEqualTo(2);
    }

    /** The rows a delivery would have inserted and then died holding (claimed_at as given). */
    private void insertClaimedRows(Registered r, UUID messageId, String claimedAt) {
        Stream.of("EMAIL", "IN_APP")
                .forEach(
                        channel ->
                                jdbc.update(
                                        "INSERT INTO notifications (source_event_id, user_id,"
                                                + " channel, type, related_entity_type,"
                                                + " related_entity_id, title, body, claimed_at)"
                                                + " VALUES (?, ?, ?, 'REGISTRATION_CONFIRMED',"
                                                + " 'REGISTRATION', ?, ?, ?, "
                                                + claimedAt
                                                + ")",
                                        messageId,
                                        r.user(),
                                        channel,
                                        r.registration(),
                                        channel.equals("IN_APP") ? "Redelivery " + r.token() : null,
                                        channel.equals("IN_APP") ? "body" : null));
    }
}
