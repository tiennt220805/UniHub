package com.unihub.notification.infrastructure.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.notification.application.command.DispatchNotificationsCommand;
import com.unihub.notification.application.command.NotificationFact;
import com.unihub.notification.application.port.in.DispatchNotificationsUseCase;
import com.unihub.registration.api.event.RegistrationConfirmedIntegrationEvent;
import com.unihub.shared.messaging.NonRetryableMessageException;
import com.unihub.workshop.api.event.WorkshopCancelledIntegrationEvent;
import com.unihub.workshop.api.event.WorkshopRescheduledIntegrationEvent;
import java.io.IOException;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The one consumer of {@code unihub.notification.queue} (messaging.md §2, notification.md §3; Phase
 * 4 checklist D34). The queue holds three payload kinds and, after a retry hop, the routing key a
 * message arrives with is the stage's fixed redelivery key, so the listener takes the raw {@link
 * Message} and chooses the payload class from the AMQP {@code type} property the outbox relay sets
 * to the row's routing key. A message without {@code type} (published before the property existed)
 * falls back to {@code x-original-routing-key}, then to the received routing key; a kind that still
 * cannot be told is permanent. This is the documented exception to "the converter infers the type
 * from the listener parameter" (backend-coding-standards.md §8.5).
 *
 * <p>It maps and delegates; it never catches, so a failure reaches the shared error handler.
 */
@Component
class NotificationEventListener {
    static final String QUEUE = "unihub.notification.queue";
    static final String ORIGINAL_ROUTING_KEY_HEADER = "x-original-routing-key";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final String TICKET_CONFIRMED =
            RegistrationConfirmedIntegrationEvent.ROUTING_KEY;
    private static final String WORKSHOP_CANCELLED = WorkshopCancelledIntegrationEvent.ROUTING_KEY;
    private static final String WORKSHOP_RESCHEDULED =
            WorkshopRescheduledIntegrationEvent.ROUTING_KEY;

    private final DispatchNotificationsUseCase dispatchNotifications;
    private final ObjectMapper json;

    NotificationEventListener(
            DispatchNotificationsUseCase dispatchNotifications, ObjectMapper json) {
        this.dispatchNotifications = dispatchNotifications;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE, containerFactory = "notificationListenerContainerFactory")
    void onLifecycleEvent(Message message) {
        var properties = message.getMessageProperties();
        var sourceEventId = sourceEventId(properties.getMessageId());
        var kind = kindOf(message);
        var payload = read(message);
        checkSchemaVersion(payload);
        var fact = factOf(kind, payload);
        dispatchNotifications.dispatch(new DispatchNotificationsCommand(sourceEventId, fact));
    }

    private static UUID sourceEventId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new NonRetryableMessageException("message without a message-id");
        }
        try {
            return UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableMessageException("message-id is not a UUID: " + messageId, e);
        }
    }

    private static String kindOf(Message message) {
        var properties = message.getMessageProperties();
        var type = properties.getType();
        if (type != null && !type.isBlank()) {
            return type;
        }
        Object original = properties.getHeader(ORIGINAL_ROUTING_KEY_HEADER);
        if (original instanceof String key && !key.isBlank()) {
            return key;
        }
        var received = properties.getReceivedRoutingKey();
        if (received != null && !received.isBlank()) {
            return received;
        }
        throw new NonRetryableMessageException("cannot tell what kind of message this is");
    }

    private JsonNode read(Message message) {
        try {
            return json.readTree(message.getBody());
        } catch (IOException e) {
            throw new NonRetryableMessageException("the body is not JSON", e);
        }
    }

    private static void checkSchemaVersion(JsonNode payload) {
        var version = payload.path("schemaVersion");
        if (!version.isInt() || version.intValue() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException("unsupported schemaVersion " + version);
        }
    }

    private NotificationFact factOf(String kind, JsonNode payload) {
        try {
            return switch (kind) {
                case TICKET_CONFIRMED -> {
                    var event =
                            json.treeToValue(payload, RegistrationConfirmedIntegrationEvent.class);
                    require(event.registrationId(), event.workshopId(), event.userId());
                    yield new NotificationFact.RegistrationConfirmed(
                            event.registrationId(), event.workshopId(), event.userId());
                }
                case WORKSHOP_CANCELLED -> {
                    var event = json.treeToValue(payload, WorkshopCancelledIntegrationEvent.class);
                    require(event.workshopId());
                    yield new NotificationFact.WorkshopCancelled(event.workshopId());
                }
                case WORKSHOP_RESCHEDULED -> {
                    var event =
                            json.treeToValue(payload, WorkshopRescheduledIntegrationEvent.class);
                    require(
                            event.workshopId(),
                            event.previousStartTime(),
                            event.previousEndTime(),
                            event.newStartTime(),
                            event.newEndTime());
                    yield new NotificationFact.WorkshopRescheduled(
                            event.workshopId(),
                            event.previousStartTime(),
                            event.previousEndTime(),
                            event.newStartTime(),
                            event.newEndTime());
                }
                default -> throw new NonRetryableMessageException("unknown message type " + kind);
            };
        } catch (IOException e) {
            throw new NonRetryableMessageException("the " + kind + " payload is unreadable", e);
        }
    }

    private static void require(Object... fields) {
        for (var field : fields) {
            if (field == null) {
                throw new NonRetryableMessageException("a required field is missing");
            }
        }
    }
}
