package com.unihub.shared.infrastructure.out.messaging;

import com.unihub.shared.application.port.out.BrokerPublishException;
import com.unihub.shared.application.port.out.BrokerUnavailableException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Sends one message and returns only once the broker has confirmed it <em>and</em> routed it.
 * Anything less throws. A returned (unroutable) message is still acked by the broker, so the ack
 * alone proves nothing: the return is checked explicitly.
 *
 * <p>Used wherever losing the message is not acceptable: the outbox relay and the retry/parking
 * republish.
 */
final class ConfirmedSender {

    private final RabbitTemplate template;
    private final Duration confirmTimeout;

    ConfirmedSender(RabbitTemplate template, Duration confirmTimeout) {
        this.template = template;
        this.confirmTimeout = confirmTimeout;
    }

    void send(String exchange, String routingKey, Message message) {
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.send(exchange, routingKey, message, correlation);
        } catch (AmqpConnectException e) {
            throw new BrokerUnavailableException(e.getMessage(), e);
        } catch (AmqpException e) {
            throw new BrokerPublishException("publish failed: " + e.getMessage(), e);
        }
        try {
            var confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new BrokerPublishException(
                        "broker nacked the message: " + confirm.getReason());
            }
        } catch (TimeoutException e) {
            throw new BrokerPublishException("no publisher confirm within " + confirmTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerPublishException("interrupted while waiting for the confirm", e);
        } catch (ExecutionException e) {
            throw new BrokerPublishException("publisher confirm failed", e.getCause());
        }
        var returned = correlation.getReturned();
        if (returned != null) {
            throw new BrokerPublishException(
                    "unroutable: " + returned.getReplyText() + " (routing key " + routingKey + ")");
        }
    }
}
