package com.unihub.shared.application.port.out;

import java.util.UUID;

/**
 * An {@code event_publications} row waiting for the broker (database-schema.md §8.2). {@code
 * payload} is the JSON body exactly as stored; {@code correlationId} is null for a row written
 * outside any traced context.
 */
public record PendingEvent(UUID id, String routingKey, String payload, String correlationId) {}
