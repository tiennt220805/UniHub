package com.unihub.shared.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * The relay's view of the outbox (messaging.md §5). Deliberately not transactional: the relay holds
 * no transaction while it talks to the broker, so each call here is its own autocommit statement on
 * the Master — a Replica read could return a row already marked {@code PUBLISHED}.
 */
public interface PendingEventStore {

    /** Up to {@code limit} {@code PENDING} rows, oldest first, without locking them. */
    List<PendingEvent> findPending(int limit);

    /**
     * {@code PENDING} to {@code PUBLISHED}.
     *
     * @return false when the row was no longer {@code PENDING} (another run published it first)
     */
    boolean markPublished(UUID id);
}
