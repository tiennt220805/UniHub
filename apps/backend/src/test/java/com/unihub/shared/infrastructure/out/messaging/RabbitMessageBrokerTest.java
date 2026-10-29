package com.unihub.shared.infrastructure.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.unihub.shared.application.port.out.PendingEvent;
import com.unihub.shared.infrastructure.config.MessagingProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitMessageBrokerTest {

    private static final String ROUTING_KEY = "unihub.workshop.event.rescheduled";

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RabbitMessageBroker broker =
            new RabbitMessageBroker(
                    template,
                    new MessagingProperties(Duration.ofSeconds(1), Topology.RETRY_DELAYS));

    @BeforeEach
    void confirmEverySend() {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(template)
                .send(
                        any(String.class),
                        any(String.class),
                        any(Message.class),
                        any(CorrelationData.class));
    }

    private Message publish(PendingEvent event) {
        broker.publish(event);
        var sent = ArgumentCaptor.forClass(Message.class);
        verify(template)
                .send(
                        eq(Topology.EXCHANGE),
                        eq(event.routingKey()),
                        sent.capture(),
                        any(CorrelationData.class));
        return sent.getValue();
    }

    @Test
    @DisplayName("the AMQP type property is the row's routing key (D34)")
    void type_isTheRoutingKey() {
        var message = publish(new PendingEvent(UUID.randomUUID(), ROUTING_KEY, "{}", null));

        assertThat(message.getMessageProperties().getType()).isEqualTo(ROUTING_KEY);
    }

    @Test
    @DisplayName("the type follows each row, so one queue can hold several kinds")
    void type_differsPerRow() {
        var cancelled =
                publish(
                        new PendingEvent(
                                UUID.randomUUID(), "unihub.workshop.event.cancelled", "{}", null));

        assertThat(cancelled.getMessageProperties().getType())
                .isEqualTo("unihub.workshop.event.cancelled");
    }

    @Test
    @DisplayName("message-id stays the outbox row id, the body stays the stored JSON")
    void messageIdAndBody_areUnchanged() {
        var id = UUID.randomUUID();
        var message = publish(new PendingEvent(id, ROUTING_KEY, "{\"workshopId\":\"w\"}", null));

        var properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(id.toString());
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"workshopId\":\"w\"}");
    }
}
