package com.unihub.shared.infrastructure.out.messaging;

import com.unihub.shared.infrastructure.config.AuditProperties;
import com.unihub.shared.infrastructure.out.messaging.AuditPublishException.Reason;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

/**
 * Publishes audit messages to {@code unihub.system.audit.created} (messaging.md §3, Phase 6
 * checklist D68), waiting for the publisher confirm so that a nack or a silent broker is counted.
 *
 * <p>It owns a connection of its own, built here and deliberately <em>not</em> a Spring bean: a
 * second {@code ConnectionFactory} or {@code RabbitOperations} bean would make Boot's
 * auto-configuration back off, taking down the outbox relay and every listener. With a connection
 * of its own, a stalled audit path (a broker under a memory alarm blocks publishing connections)
 * can never hold up the relay, which uses Boot's.
 *
 * <p>Not {@link ConfirmedSender}: that one folds a timeout into "unavailable" and a nack into
 * "publish failed", which is the relay's distinction, not the counter's.
 */
public final class AmqpAuditPublisher implements AuditPublisher, DisposableBean {

    private final RabbitTemplate template;
    private final Duration confirmTimeout;
    private final Runnable closer;

    public AmqpAuditPublisher(RabbitProperties rabbit, AuditProperties properties) {
        this(connectionFactory(rabbit, properties), properties.confirmTimeout());
    }

    private AmqpAuditPublisher(CachingConnectionFactory connections, Duration confirmTimeout) {
        this(template(connections), confirmTimeout, connections::destroy);
    }

    /** For tests: a template that needs no broker. */
    AmqpAuditPublisher(RabbitTemplate template, Duration confirmTimeout, Runnable closer) {
        this.template = template;
        this.confirmTimeout = confirmTimeout;
        this.closer = closer;
    }

    private static CachingConnectionFactory connectionFactory(
            RabbitProperties rabbit, AuditProperties properties) {
        // The client factory is built directly: Boot applies channel-rpc-timeout and the handshake
        // timeout to it, and CachingConnectionFactory has no setter for either.
        var client = new com.rabbitmq.client.ConnectionFactory();
        client.setHost(rabbit.determineHost());
        client.setPort(rabbit.determinePort());
        client.setUsername(rabbit.determineUsername());
        client.setPassword(rabbit.determinePassword());
        var virtualHost = rabbit.determineVirtualHost();
        if (virtualHost != null) {
            client.setVirtualHost(virtualHost);
        }
        var timeout = (int) properties.connectionTimeout().toMillis();
        client.setConnectionTimeout(timeout);
        client.setHandshakeTimeout(timeout);
        client.setChannelRpcTimeout(timeout);
        var connections = new CachingConnectionFactory(client);
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        connections.setChannelCacheSize(1);
        connections.setConnectionNameStrategy(factory -> "unihub-audit");
        return connections;
    }

    private static RabbitTemplate template(CachingConnectionFactory connections) {
        var template = new RabbitTemplate(connections);
        template.setMandatory(true);
        return template;
    }

    @Override
    public void publish(String messageId, byte[] body, String correlationId) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(messageId);
        properties.setType(Topology.AUDIT_CREATED);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (correlationId != null) {
            // Set here, not by the CorrelationIdPostProcessor: this template is not the one Boot
            // builds, and the executor thread's MDC is not the request's.
            properties.setHeader(CorrelationIdPostProcessor.HEADER, correlationId);
        }
        var correlation = new CorrelationData(messageId);
        try {
            template.send(
                    Topology.EXCHANGE,
                    Topology.AUDIT_CREATED,
                    new Message(body, properties),
                    correlation);
        } catch (AmqpException e) {
            // A channel that cannot be opened in time is the same silent broker that would not
            // have confirmed.
            throw new AuditPublishException(
                    isTimeout(e) ? Reason.TIMEOUT : Reason.ERROR,
                    "audit publish failed: " + e.getMessage(),
                    e);
        }
        try {
            var confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new AuditPublishException(
                        Reason.NACK,
                        "broker nacked the audit message: " + confirm.getReason(),
                        null);
            }
        } catch (TimeoutException e) {
            throw new AuditPublishException(
                    Reason.TIMEOUT, "no publisher confirm within " + confirmTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditPublishException(
                    Reason.ERROR, "interrupted while waiting for the confirm", e);
        } catch (ExecutionException e) {
            throw new AuditPublishException(Reason.ERROR, "publisher confirm failed", e.getCause());
        }
        if (correlation.getReturned() != null) {
            throw new AuditPublishException(
                    Reason.ERROR, "unroutable: " + correlation.getReturned().getReplyText(), null);
        }
    }

    /** Closing the connection also unblocks a thread stuck writing to a blocked socket. */
    @Override
    public void destroy() {
        closer.run();
    }

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
