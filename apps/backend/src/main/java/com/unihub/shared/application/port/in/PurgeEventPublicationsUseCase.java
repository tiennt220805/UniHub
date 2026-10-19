package com.unihub.shared.application.port.in;

import java.time.Duration;

/** The scheduled sweep of published outbox rows (distributed-coordination.md §2). */
public interface PurgeEventPublicationsUseCase {

    /**
     * Deletes {@code PUBLISHED} rows published more than {@code retention} ago.
     *
     * @return how many rows were deleted
     */
    int purge(Duration retention);
}
