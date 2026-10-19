package com.unihub.shared.application.port.out;

/**
 * The only way outbox traffic reaches RabbitMQ (backend-coding-standards.md §8.3). Returns only
 * when the broker has confirmed the message and routed it to at least one queue.
 */
public interface MessageBroker {

    /**
     * @throws BrokerPublishException this message was not delivered (nack, no confirm in time,
     *     unroutable); other messages may still go through
     * @throws BrokerUnavailableException the broker cannot be reached at all; trying the next
     *     message would only wait again
     */
    void publish(PendingEvent event);
}
