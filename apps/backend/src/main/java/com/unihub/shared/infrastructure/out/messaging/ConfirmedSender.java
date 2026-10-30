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
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Sends one message and returns only once the broker has confirmed it <em>and</em> routed it.
 * Anything less throws. A returned (unroutable) message is still acked by the broker, so the ack
 * alone proves nothing: the return is checked explicitly.
 *
 * <p>Two kinds of failure, told apart by what they say about the broker. {@link
 * BrokerUnavailableException}: the connection is refused or the confirm does not come in time, so
 * the next message would fail the same way. {@link BrokerPublishException}: the broker answered and
 * refused this message (a nack, an unroutable return, a protocol error).
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
            if (isTimeout(e)) {
                // Opening the channel for this send got no answer: the same broker that would not
                // confirm. Bounded by spring.rabbitmq.channel-rpc-timeout (application.yml).
                throw new BrokerUnavailableException("broker did not answer: " + e.getMessage(), e);
            }
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
            // A broker that does not confirm is unhealthy as a whole, not this one message: the
            // relay ends its cycle here instead of waiting out the same timeout for every row
            // behind it (messaging.md §5, Phase 4 checklist D42).
            throw new BrokerUnavailableException(
                    "no publisher confirm within " + confirmTimeout, e);
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

    /** A wait that ran out, however the client wrapped it. */
    private static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof AmqpTimeoutException || t instanceof TimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
