package com.unihub.shared.infrastructure.out.messaging;

import com.unihub.shared.application.port.out.MessageBroker;
import com.unihub.shared.application.port.out.PendingEvent;
import com.unihub.shared.infrastructure.config.MessagingProperties;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The only class that publishes outbox traffic (backend-coding-standards.md §8.3). The row's JSON
 * payload goes out as the body unchanged — converting it again would encode it a second time as a
 * JSON string. {@code message-id} is the outbox row id, the event id consumers deduplicate on.
 *
 * <p>{@code type} is the row's routing key, on every message (Phase 4 checklist D34, messaging.md
 * §2). It is the only thing that still tells a consumer which payload it holds once the message has
 * been through a retry hop, where the received routing key becomes the stage's fixed redelivery
 * key. A property of the {@link Message} itself, it survives the retry chain and the parking lot,
 * which republish that same object.
 */
@Component
@EnableConfigurationProperties(MessagingProperties.class)
class RabbitMessageBroker implements MessageBroker {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitMessageBroker.class);

    private final ConfirmedSender sender;

    RabbitMessageBroker(RabbitTemplate template, MessagingProperties properties) {
        this.sender = new ConfirmedSender(template, properties.confirmTimeout());
    }

    @Override
    public void publish(PendingEvent event) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(event.id().toString());
        properties.setType(event.routingKey());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var message = new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties);

        // The relay runs outside the request that wrote the row. The stored correlation id goes
        // back into the MDC so the template's post-processor writes it into the message header; a
        // row without one keeps the id of the relay cycle that is publishing it. Whatever the MDC
        // held before is put back afterwards.
        var previous = MDC.get(CorrelationIdPostProcessor.MDC_KEY);
        if (event.correlationId() != null) {
            MDC.put(CorrelationIdPostProcessor.MDC_KEY, event.correlationId());
        }
        try {
            sender.send(Topology.EXCHANGE, event.routingKey(), message);
            LOG.info(
                    "Published event {} routingKey={} correlationId={}",
                    event.id(),
                    event.routingKey(),
                    event.correlationId());
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIdPostProcessor.MDC_KEY);
            } else {
                MDC.put(CorrelationIdPostProcessor.MDC_KEY, previous);
            }
        }
    }
}
