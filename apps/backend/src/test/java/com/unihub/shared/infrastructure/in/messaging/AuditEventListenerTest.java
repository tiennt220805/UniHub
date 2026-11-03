package com.unihub.shared.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unihub.shared.application.command.RecordAuditEventCommand;
import com.unihub.shared.application.command.RecordAuditEventCommand.ActorType;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * What the audit consumer maps, and what it refuses for good (Phase 6 checklist D69). A message
 * that can never be written is a {@link NonRetryableMessageException}, which the error handler
 * sends straight to the parking lot; retrying the same bytes three times would only delay the
 * alert.
 */
class AuditEventListenerTest {

    private static final UUID MESSAGE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    private final ObjectMapper json =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);
    private final List<RecordAuditEventCommand> recorded = new ArrayList<>();
    private final AuditEventListener listener = new AuditEventListener(recorded::add, json);

    private static Message message(String messageId, String body) {
        var properties = new MessageProperties();
        properties.setMessageId(messageId);
        return new Message(body.getBytes(), properties);
    }

    private static String body(String actorType, String actorUserId, String schemaVersion) {
        return """
        {"schemaVersion":%s,"actorType":"%s","actorUserId":%s,"action":"WORKSHOP_CANCELLED",
         "targetType":"WORKSHOP","targetId":"%s","occurredAt":"2026-11-03T08:00:00Z",
         "metadata":{"process":"seat-hold-expiry","count":3}}
        """
                .formatted(
                        schemaVersion,
                        actorType,
                        actorUserId == null ? "null" : "\"" + actorUserId + "\"",
                        TARGET);
    }

    @Test
    @DisplayName("a user message becomes a command with the message-id as the source")
    void userMessage() {
        listener.onAuditCreated(message(MESSAGE.toString(), body("USER", USER.toString(), "1")));

        assertThat(recorded).hasSize(1);
        var command = recorded.getFirst();
        assertThat(command.sourceMessageId()).isEqualTo(MESSAGE);
        assertThat(command.actorType()).isEqualTo(ActorType.USER);
        assertThat(command.actorUserId()).isEqualTo(USER);
        assertThat(command.action()).isEqualTo("WORKSHOP_CANCELLED");
        assertThat(command.targetType()).isEqualTo("WORKSHOP");
        assertThat(command.targetId()).isEqualTo(TARGET);
        assertThat(command.occurredAt()).isEqualTo(Instant.parse("2026-11-03T08:00:00Z"));
        assertThat(command.metadata()).containsEntry("process", "seat-hold-expiry");
    }

    @Test
    @DisplayName("a system message has no user")
    void systemMessage() {
        listener.onAuditCreated(message(MESSAGE.toString(), body("SYSTEM", null, "1")));

        assertThat(recorded.getFirst().actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(recorded.getFirst().actorUserId()).isNull();
    }

    @Test
    @DisplayName("a message without a message-id would slip past the unique index: parked")
    void withoutAMessageId() {
        assertThatThrownBy(
                        () ->
                                listener.onAuditCreated(
                                        message(null, body("USER", USER.toString(), "1"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onAuditCreated(
                                        message("not-a-uuid", body("USER", USER.toString(), "1"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("an unsupported or missing schemaVersion is parked")
    void unsupportedSchemaVersion() {
        assertThatThrownBy(
                        () ->
                                listener.onAuditCreated(
                                        message(
                                                MESSAGE.toString(),
                                                body("USER", USER.toString(), "2"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onAuditCreated(
                                        message(
                                                MESSAGE.toString(),
                                                body("USER", USER.toString(), "null"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName(
            "a body that is not an audit message, an unknown actor type, or a USER actor"
                    + " without a user is parked")
    void malformed() {
        assertThatThrownBy(() -> listener.onAuditCreated(message(MESSAGE.toString(), "not json")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onAuditCreated(
                                        message(MESSAGE.toString(), body("ROBOT", null, "1"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onAuditCreated(
                                        message(MESSAGE.toString(), body("USER", null, "1"))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("a database failure is not swallowed: it must reach the retry chain")
    void aFailureOfTheUseCasePropagates() {
        var failing =
                new AuditEventListener(
                        command -> {
                            throw new IllegalStateException("db down");
                        },
                        json);

        assertThatThrownBy(
                        () ->
                                failing.onAuditCreated(
                                        message(
                                                MESSAGE.toString(),
                                                body("USER", USER.toString(), "1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }
}
