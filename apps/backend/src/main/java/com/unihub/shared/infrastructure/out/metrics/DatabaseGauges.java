package com.unihub.shared.infrastructure.out.metrics;

import com.unihub.shared.application.port.out.OutboxBacklog;
import com.unihub.shared.application.port.out.ReplicaLag;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The two cluster-wide gauges the shared kernel owns (observability.md §4, Phase 6 checklist D76),
 * each read from PostgreSQL every 10 seconds on a thread of its own:
 *
 * <ul>
 *   <li>{@code unihub_outbox_oldest_pending_age_seconds}: 0 when nothing waits.
 *   <li>{@code unihub_postgres_replica_lag_seconds}: NaN, never 0, for "unknown" (the instance is
 *       not a Replica, or has replayed nothing), so an absent Replica cannot look like a healthy
 *       one; 0 only when a Replica has replayed all it received, so an idle primary is not lag.
 * </ul>
 */
@Component
class DatabaseGauges {
    static final String OUTBOX_AGE = "unihub.outbox.oldest.pending.age.seconds";
    static final String REPLICA_LAG = "unihub.postgres.replica.lag.seconds";

    DatabaseGauges(
            OutboxBacklog outbox,
            ReplicaLag replica,
            MeterRegistry registry,
            BackgroundGauges gauges) {
        gauges.register(
                registry,
                OUTBOX_AGE,
                "Age of the oldest outbox row still PENDING; 0 when none waits",
                "seconds",
                () ->
                        outbox.oldestPendingAge()
                                .map(age -> age.toNanos() / 1_000_000_000.0)
                                .orElse(0.0));
        gauges.register(
                registry,
                REPLICA_LAG,
                "Seconds the read Replica trails the Master; NaN when not a Replica",
                "seconds",
                () -> replica.lagSeconds().orElse(Double.NaN));
    }
}
