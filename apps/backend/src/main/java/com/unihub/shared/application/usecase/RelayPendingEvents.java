package com.unihub.shared.application.usecase;

import com.unihub.shared.application.port.in.RelayPendingEventsUseCase;
import com.unihub.shared.application.port.out.BrokerPublishException;
import com.unihub.shared.application.port.out.BrokerUnavailableException;
import com.unihub.shared.application.port.out.MessageBroker;
import com.unihub.shared.application.port.out.PendingEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deliberately not {@code @Transactional} (messaging.md §5, backend-coding-standards.md §19.1): a
 * transaction held across broker round-trips would pin a pooled connection for their whole
 * duration. Every store call is its own autocommit statement.
 *
 * <p>At-least-once: a crash after the broker's confirm but before {@code markPublished} publishes
 * the row again next cycle. Consumers deduplicate (§8.5).
 */
@Service
class RelayPendingEvents implements RelayPendingEventsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(RelayPendingEvents.class);

    private final PendingEventStore store;
    private final MessageBroker broker;

    RelayPendingEvents(PendingEventStore store, MessageBroker broker) {
        this.store = store;
        this.broker = broker;
    }

    @Override
    public int relay(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        int published = 0;
        for (var event : store.findPending(batchSize)) {
            try {
                broker.publish(event);
            } catch (BrokerPublishException e) {
                LOG.warn(
                        "Outbox row {} ({}) stays PENDING: {}",
                        event.id(),
                        event.routingKey(),
                        e.getMessage());
                continue;
            } catch (BrokerUnavailableException e) {
                LOG.warn("Outbox relay cycle ended early, broker unavailable: {}", e.getMessage());
                break;
            }
            if (store.markPublished(event.id())) {
                published++;
            }
        }
        return published;
    }
}
