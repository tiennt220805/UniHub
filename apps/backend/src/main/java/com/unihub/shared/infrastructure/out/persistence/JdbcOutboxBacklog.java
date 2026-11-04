package com.unihub.shared.infrastructure.out.persistence;

import com.unihub.shared.application.port.out.OutboxBacklog;
import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Served by {@code idx_event_publications_pending}. The age is computed by the database against its
 * own clock, the one that stamped {@code created_at}. No transaction on purpose: the routing
 * datasource sends a statement outside one to the Master.
 */
@Repository
class JdbcOutboxBacklog implements OutboxBacklog {
    private final JdbcTemplate jdbc;

    JdbcOutboxBacklog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Duration> oldestPendingAge() {
        var seconds =
                jdbc.queryForObject(
                        "SELECT EXTRACT(EPOCH FROM (now() - min(created_at)))::float8"
                                + " FROM event_publications WHERE status = 'PENDING'",
                        Double.class);
        return Optional.ofNullable(seconds)
                .map(s -> Duration.ofNanos((long) (Math.max(0.0, s) * 1_000_000_000L)));
    }
}
