package com.unihub.shared.application.port.in;

import java.time.Duration;

/** The scheduled sweep of expired idempotency keys (distributed-coordination.md §2). */
public interface PurgeIdempotencyKeysUseCase {

    /**
     * Deletes {@code COMPLETED} keys completed more than {@code retention} ago.
     *
     * @return how many keys were deleted
     */
    int purge(Duration retention);
}
