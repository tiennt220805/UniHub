package com.unihub.shared.application.port.out;

import com.unihub.shared.event.OutboxMessage;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the intent to publish a message in the transactional outbox (backend-coding-standards.md
 * §8.3, architecture.md §4.3, §6.1). Business code never publishes to RabbitMQ itself.
 *
 * <p>The row is written in the caller's transaction ({@code MANDATORY}): a message that commits
 * alone would announce a business change that may still roll back, and a change that commits
 * without its message would never be announced.
 */
public interface EventPublisher {

    @Transactional(propagation = Propagation.MANDATORY)
    void publish(OutboxMessage message);
}
