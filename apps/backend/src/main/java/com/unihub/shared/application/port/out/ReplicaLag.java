package com.unihub.shared.application.port.out;

import java.util.OptionalDouble;

/** How far the read Replica trails the Master (observability.md §4, architecture.md §4.1). */
public interface ReplicaLag {

    /**
     * Seconds the Replica's replay is behind, or empty when there is no lag to report: the instance
     * asked is not in recovery (a single-instance local stack reads the Master) or has replayed no
     * transaction yet. Never 0 for "unknown" ({@code unihub_postgres_replica_lag_seconds}).
     */
    OptionalDouble lagSeconds();
}
