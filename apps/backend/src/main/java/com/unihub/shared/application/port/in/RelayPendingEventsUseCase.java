package com.unihub.shared.application.port.in;

/** One cycle of the outbox relay (messaging.md §5). */
public interface RelayPendingEventsUseCase {

    /**
     * Publishes up to {@code batchSize} pending rows and marks each {@code PUBLISHED} after the
     * broker's confirm. A row that fails stays {@code PENDING} and the rows behind it still go.
     *
     * @return how many rows were published
     */
    int relay(int batchSize);
}
