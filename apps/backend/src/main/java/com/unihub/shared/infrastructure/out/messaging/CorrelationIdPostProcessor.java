package com.unihub.shared.infrastructure.out.messaging;

import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

/**
 * Copies the MDC {@code correlationId} onto every outgoing message as the {@code correlationId}
 * header (observability.md §1). The outbox relay puts a row's stored correlation id into the MDC
 * just before publishing, so a message consumed minutes later still links back to the request that
 * caused it. A header already on the message is kept: a republished retry carries its original.
 */
public final class CorrelationIdPostProcessor implements MessagePostProcessor {

    public static final String HEADER = "correlationId";
    public static final String MDC_KEY = "correlationId";

    @Override
    public Message postProcessMessage(Message message) throws AmqpException {
        var correlationId = MDC.get(MDC_KEY);
        if (correlationId != null && message.getMessageProperties().getHeader(HEADER) == null) {
            message.getMessageProperties().setHeader(HEADER, correlationId);
        }
        return message;
    }
}
