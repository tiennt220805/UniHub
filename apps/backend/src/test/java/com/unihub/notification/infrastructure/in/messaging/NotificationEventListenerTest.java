package com.unihub.notification.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unihub.notification.application.command.DispatchNotificationsCommand;
import com.unihub.notification.application.command.NotificationFact;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * How the listener tells the three payload kinds apart (Phase 4 checklist D34: AC34.1-AC34.3). The
 * retry hop itself, which changes the received routing key, is walked against a real broker in
 * {@code NotificationEventTypeIT}.
 */
class NotificationEventListenerTest {

    private static final String CONFIRMED = "unihub.registration.ticket.confirmed";
    private static final String CANCELLED = "unihub.workshop.event.cancelled";
    private static final String RESCHEDULED = "unihub.workshop.event.rescheduled";
    private static final String REDELIVERY = "unihub.notification.redelivery.requested";

    private final List<DispatchNotificationsCommand> dispatched = new ArrayList<>();
    private final ObjectMapper json =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);
    private final NotificationEventListener listener =
            new NotificationEventListener(dispatched::add, json);

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID WORKSHOP = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID REGISTRATION = UUID.randomUUID();

    private static final String CONFIRMED_BODY =
            "{\"registrationId\":\""
                    + REGISTRATION
                    + "\",\"workshopId\":\""
                    + WORKSHOP
                    + "\",\"userId\":\""
                    + USER
                    + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\","
                    + "\"schemaVersion\":1}";
    private static final String CANCELLED_BODY =
            "{\"workshopId\":\""
                    + WORKSHOP
                    + "\",\"occurredAt\":\"2026-11-01T00:00:00Z\","
                    + "\"schemaVersion\":1}";
    private static final String RESCHEDULED_BODY =
            "{\"workshopId\":\""
                    + WORKSHOP
                    + "\",\"previousStartTime\":\"2026-11-01T01:00:00Z\","
                    + "\"previousEndTime\":\"2026-11-01T03:00:00Z\",\"newStartTime\":\"2026-11-02T01:00:00Z\","
                    + "\"newEndTime\":\"2026-11-02T03:00:00Z\",\"occurredAt\":\"2026-11-01T00:00:00Z\","
                    + "\"schemaVersion\":1}";

    private static Message message(
            String body, String messageId, String type, String received, String original) {
        var properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setType(type);
        properties.setReceivedRoutingKey(received);
        if (original != null) {
            properties.setHeader("x-original-routing-key", original);
        }
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static Message typed(String body, String type) {
        return message(body, EVENT.toString(), type, type, null);
    }

    @Test
    @DisplayName("each of the three kinds becomes its own fact, keyed by the message-id")
    void threeKinds_becomeThreeFacts() {
        listener.onLifecycleEvent(typed(CONFIRMED_BODY, CONFIRMED));
        listener.onLifecycleEvent(typed(CANCELLED_BODY, CANCELLED));
        listener.onLifecycleEvent(typed(RESCHEDULED_BODY, RESCHEDULED));

        assertThat(dispatched)
                .extracting(DispatchNotificationsCommand::sourceEventId)
                .containsOnly(EVENT);
        assertThat(dispatched.get(0).fact())
                .isEqualTo(
                        new NotificationFact.RegistrationConfirmed(REGISTRATION, WORKSHOP, USER));
        assertThat(dispatched.get(1).fact())
                .isEqualTo(new NotificationFact.WorkshopCancelled(WORKSHOP));
        assertThat(dispatched.get(2).fact())
                .isInstanceOf(NotificationFact.WorkshopRescheduled.class);
        var rescheduled = (NotificationFact.WorkshopRescheduled) dispatched.get(2).fact();
        assertThat(rescheduled.newStartTime().toString()).isEqualTo("2026-11-02T01:00:00Z");
        assertThat(rescheduled.previousEndTime().toString()).isEqualTo("2026-11-01T03:00:00Z");
    }

    @Test
    @DisplayName(
            "after a retry hop the received key is the redelivery key, and type still decides (AC34.1)")
    void typeWins_overTheReceivedRoutingKey() {
        listener.onLifecycleEvent(
                message(CANCELLED_BODY, EVENT.toString(), CANCELLED, REDELIVERY, CANCELLED));

        assertThat(dispatched.getFirst().fact())
                .isEqualTo(new NotificationFact.WorkshopCancelled(WORKSHOP));
    }

    @Test
    @DisplayName(
            "a message without type falls back to the original routing key, then to the received one (AC34.3)")
    void noType_fallsBack() {
        listener.onLifecycleEvent(
                message(CANCELLED_BODY, EVENT.toString(), null, REDELIVERY, CANCELLED));
        listener.onLifecycleEvent(message(CANCELLED_BODY, EVENT.toString(), null, CANCELLED, null));

        assertThat(dispatched).hasSize(2);
        assertThat(dispatched)
                .extracting(DispatchNotificationsCommand::fact)
                .containsOnly(new NotificationFact.WorkshopCancelled(WORKSHOP));
    }

    @Test
    @DisplayName("a kind that cannot be determined, or is unknown, is permanent")
    void unknownKind_isNonRetryable() {
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        message(
                                                CANCELLED_BODY,
                                                EVENT.toString(),
                                                null,
                                                REDELIVERY,
                                                null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        typed(CANCELLED_BODY, "unihub.payment.charge.succeeded")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        message(
                                                CANCELLED_BODY,
                                                EVENT.toString(),
                                                null,
                                                null,
                                                null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(dispatched).isEmpty();
    }

    @Test
    @DisplayName("no message-id, or one that is not a UUID, is permanent: it keys the dedup")
    void badMessageId_isNonRetryable() {
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        message(CANCELLED_BODY, null, CANCELLED, CANCELLED, null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        message(CANCELLED_BODY, "  ", CANCELLED, CANCELLED, null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        message(
                                                CANCELLED_BODY,
                                                "not-a-uuid",
                                                CANCELLED,
                                                CANCELLED,
                                                null)))
                .isInstanceOf(NonRetryableMessageException.class);
    }

    @Test
    @DisplayName(
            "an unknown schemaVersion, a body that is not JSON, or a missing field is permanent")
    void unreadablePayload_isNonRetryable() {
        var wrongVersion = CANCELLED_BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        var noVersion = CANCELLED_BODY.replace(",\"schemaVersion\":1", "");
        var noWorkshop = "{\"occurredAt\":\"2026-11-01T00:00:00Z\",\"schemaVersion\":1}";
        for (var body : List.of(wrongVersion, noVersion, noWorkshop, "this is {not json")) {
            assertThatThrownBy(() -> listener.onLifecycleEvent(typed(body, CANCELLED)))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        typed(
                                                RESCHEDULED_BODY.replace(
                                                        "\"newStartTime\":\"2026-11-02T01:00:00Z\",",
                                                        ""),
                                                RESCHEDULED)))
                .isInstanceOf(NonRetryableMessageException.class);
        // a reschedule that does not say what it moved from cannot be worded: permanent at once
        assertThatThrownBy(
                        () ->
                                listener.onLifecycleEvent(
                                        typed(
                                                RESCHEDULED_BODY.replace(
                                                        "\"previousStartTime\":\"2026-11-01T01:00:00Z\",",
                                                        ""),
                                                RESCHEDULED)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(dispatched).isEmpty();
    }
}
