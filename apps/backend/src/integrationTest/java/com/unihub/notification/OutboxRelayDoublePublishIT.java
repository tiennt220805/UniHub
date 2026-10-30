package com.unihub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.notification.application.command.DispatchNotificationsCommand;
import com.unihub.notification.application.port.in.DispatchNotificationsUseCase;
import com.unihub.shared.application.port.out.MessageBroker;
import com.unihub.shared.application.port.out.PendingEvent;
import com.unihub.shared.application.port.out.PendingEventStore;
import com.unihub.shared.infrastructure.Mailpit;
import com.unihub.shared.security.TestJwts;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A crash between the broker's confirm and the relay's {@code UPDATE} publishes the row twice, and
 * the consumer takes the second one as a no-op (Phase 4 checklist 7.3, D42, AC42.4, M-41, INV-10).
 * At-least-once is the contract of the relay; this is the test that the cost of it, a duplicate, is
 * paid by the consumer and never reaches the student.
 *
 * <p>The crash is a seam in the test, not in {@code src/main}: the {@code PendingEventStore} the
 * relay uses is wrapped so that the first {@code markPublished} of a {@code ticket.confirmed} row
 * throws after the real publish went through, which is what a pod killed at that moment leaves
 * behind. The next relay cycle finds the row {@code PENDING} and publishes it again.
 *
 * <p>The evidence that the duplicate really happened is part of the test, not assumed: two
 * publishes of the one message id at the broker and two runs of the dispatch use case for the one
 * event id, counted where they happen. The effect is then counted where the student would see it:
 * one email in Mailpit, one row per channel. That the consumer absorbs a duplicate however it
 * arrives is M-40 ({@code NotificationRedeliveryIT}); this shows the relay is one way it arrives.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OutboxRelayDoublePublishIT.Seams.class)
class OutboxRelayDoublePublishIT extends NotificationITSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LONG_ENOUGH = Duration.ofSeconds(40);

    /** Message ids at the broker, in the order published. */
    static final List<UUID> PUBLISHED = new CopyOnWriteArrayList<>();

    /** Source event ids the dispatch use case ran for, in order. */
    static final List<UUID> DISPATCHED = new CopyOnWriteArrayList<>();

    /** Rows whose {@code markPublished} was made to fail, once each. */
    static final Set<UUID> CRASHED = ConcurrentHashMap.newKeySet();

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @Autowired private TestRestTemplate http;

    @BeforeEach
    void noRowsFromEarlierTests() {
        PUBLISHED.clear();
        DISPATCHED.clear();
        CRASHED.clear();
        // the live relay would publish whatever an earlier class left committed
        jdbc.update("DELETE FROM event_publications");
    }

    /** What the relay does is untouched; only what it does around the real calls is recorded. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Seams {
        @Bean
        static BeanPostProcessor crashAfterTheConfirm() {
            return new BeanPostProcessor() {
                private final Map<UUID, String> routingKeys = new ConcurrentHashMap<>();

                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof PendingEventStore real) {
                        return new PendingEventStore() {
                            @Override
                            public List<PendingEvent> findPending(int limit) {
                                var rows = real.findPending(limit);
                                rows.forEach(r -> routingKeys.put(r.id(), r.routingKey()));
                                return rows;
                            }

                            @Override
                            public boolean markPublished(UUID id) {
                                if (CONFIRMED.equals(routingKeys.get(id)) && CRASHED.add(id)) {
                                    throw new IllegalStateException(
                                            "simulated crash between the confirm and the UPDATE");
                                }
                                return real.markPublished(id);
                            }
                        };
                    }
                    if (bean instanceof MessageBroker real) {
                        return (MessageBroker)
                                event -> {
                                    real.publish(event);
                                    PUBLISHED.add(event.id());
                                };
                    }
                    if (bean instanceof DispatchNotificationsUseCase real) {
                        return Proxy.newProxyInstance(
                                DispatchNotificationsUseCase.class.getClassLoader(),
                                new Class<?>[] {DispatchNotificationsUseCase.class},
                                (proxy, method, args) -> {
                                    try {
                                        return method.invoke(real, args);
                                    } catch (InvocationTargetException e) {
                                        throw e.getTargetException();
                                    } finally {
                                        DISPATCHED.add(
                                                ((DispatchNotificationsCommand) args[0])
                                                        .sourceEventId());
                                    }
                                });
                    }
                    return bean;
                }
            };
        }
    }

    private UUID register(UUID student, UUID workshop) throws Exception {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student, "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var response =
                http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>("{\"workshopId\":\"" + workshop + "\"}", headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.getBody());
        return UUID.fromString(body.get("id").asText());
    }

    @Test
    @DisplayName(
            "AC42.4: a row published twice because the relay died before its UPDATE is acted on"
                    + " once")
    void doublePublish_isAbsorbedByTheConsumer() throws Exception {
        var student = student();
        var workshop = aWorkshop("Double publish");

        var registration = register(student, workshop.value());
        var row =
                jdbc.queryForObject(
                        "SELECT id FROM event_publications WHERE aggregate_id = ? AND routing_key"
                                + " = ?",
                        UUID.class,
                        registration,
                        CONFIRMED);

        // the duplicate really happened: the broker took the message twice and the consumer ran
        // for the one event twice
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(PUBLISHED.stream().filter(row::equals).count())
                                    .as("publishes of the one outbox row")
                                    .isEqualTo(2);
                            assertThat(DISPATCHED.stream().filter(row::equals).count())
                                    .as("runs of the dispatch use case for the one event")
                                    .isEqualTo(2);
                        });
        assertThat(CRASHED).as("the crash was made once").containsExactly(row);

        // and the student was told once
        Awaitility.await()
                .atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(notificationRows(student, "EMAIL", "SENT")).isEqualTo(1);
                            assertThat(notificationRows(student, "IN_APP", "SENT")).isEqualTo(1);
                        });
        awaitNoOutboxRowPending();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM event_publications WHERE id = ?",
                                String.class,
                                row))
                .isEqualTo("PUBLISHED");
        assertThat(Mailpit.to(emailOf(student))).hasSize(1);
        assertThat(notificationRows(student)).as("one row per channel").isEqualTo(2);
        assertThat(PUBLISHED.stream().filter(row::equals).count())
                .as("the row is not published a third time once it is PUBLISHED")
                .isEqualTo(2);
        assertThat(queuedMessages()).isZero();
        assertThat(parkedMessages()).isZero();
    }
}
