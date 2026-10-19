package com.unihub.shared.infrastructure.in.messaging;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

/**
 * Applied to every listener container (observability.md §1), not repeated per listener: restores
 * the {@code correlationId} header into the MDC for the duration of one delivery, and clears it in
 * {@code finally} because container threads are pooled and a stale id would label the next
 * message's logs. Also logs every delivery with its event id, routing key and correlation id
 * (backend-coding-standards.md §23).
 */
public final class CorrelationIdListenerAdvice implements MethodInterceptor {

    public static final String HEADER = "correlationId";
    public static final String MDC_KEY = "correlationId";

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdListenerAdvice.class);

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        var message = messageOf(invocation);
        if (message == null) {
            return invocation.proceed();
        }
        var properties = message.getMessageProperties();
        Object correlationId = properties.getHeader(HEADER);
        if (correlationId != null) {
            MDC.put(MDC_KEY, correlationId.toString());
        }
        try {
            LOG.info(
                    "Consuming message {} routingKey={} queue={} correlationId={}",
                    properties.getMessageId(),
                    properties.getReceivedRoutingKey(),
                    properties.getConsumerQueue(),
                    correlationId);
            return invocation.proceed();
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static Message messageOf(MethodInvocation invocation) {
        for (var argument : invocation.getArguments()) {
            if (argument instanceof Message message) {
                return message;
            }
        }
        return null;
    }
}
