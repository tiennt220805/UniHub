package com.unihub.shared.application.port.out;

import java.time.Duration;
import java.util.Optional;

/** What tells an operator the outbox relay has stalled (observability.md §4). */
public interface OutboxBacklog {

    /**
     * How long the oldest {@code PENDING} row has waited, or empty when none does. Age, not count:
     * a few rows between a commit and the relay's next cycle are normal, a growing age is a stalled
     * relay ({@code unihub_outbox_oldest_pending_age_seconds}).
     */
    Optional<Duration> oldestPendingAge();
}
