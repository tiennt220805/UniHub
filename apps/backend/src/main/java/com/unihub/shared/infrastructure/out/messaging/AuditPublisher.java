package com.unihub.shared.infrastructure.out.messaging;

/**
 * Sends one audit message and returns only once the broker has confirmed it. The seam between
 * {@link RabbitAuditTrail} (queueing, counting, shutdown) and the broker, so that every branch of
 * the first can be tested without one.
 */
public interface AuditPublisher {

    /**
     * @param messageId the AMQP {@code message-id}: the consumer deduplicates on it
     * @param correlationId the request's correlation id, or {@code null} when there was none
     * @throws AuditPublishException the entry did not reach the queue, and why
     */
    void publish(String messageId, byte[] body, String correlationId);
}
