package com.unihub.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A message a use case asks the outbox to publish (backend-coding-standards.md §8.2). Implemented
 * by integration events (a module's {@code api/event}) and by Deferred Job payloads (a module's
 * {@code application/event}). Only the record components are serialized as the message body; the
 * accessors below describe where the message goes, and {@code schemaVersion} is added to the body
 * by the outbox so a consumer can reject a version it does not know.
 */
public interface OutboxMessage {

    /** The exact key from messaging.md §2, declared as a constant on the implementing record. */
    String routingKey();

    /** {@code event_publications.aggregate_type}, e.g. {@code REGISTRATION}. */
    String aggregateType();

    /** {@code event_publications.aggregate_id}: the business row this message describes. */
    UUID aggregateId();

    int schemaVersion();

    Instant occurredAt();
}
