package com.unihub.shared.infrastructure.out.persistence;

import com.unihub.shared.application.port.out.PendingEvent;
import com.unihub.shared.application.port.out.PendingEventStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link PendingEventStore} over {@code event_publications}. No transaction annotation on purpose:
 * with none open, the routing datasource sends every statement to the Master.
 */
@Repository
class JdbcPendingEventStore implements PendingEventStore {

    private static final String FIND_PENDING =
            "SELECT id, routing_key, payload::text, correlation_id FROM event_publications"
                    + " WHERE status = 'PENDING' ORDER BY created_at LIMIT ?";

    private static final String MARK_PUBLISHED =
            "UPDATE event_publications SET status = 'PUBLISHED', published_at = ?"
                    + " WHERE id = ? AND status = 'PENDING'";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcPendingEventStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<PendingEvent> findPending(int limit) {
        return jdbc.query(
                FIND_PENDING,
                (rs, row) ->
                        new PendingEvent(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4)),
                limit);
    }

    @Override
    public boolean markPublished(UUID id) {
        return jdbc.update(
                        MARK_PUBLISHED,
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                        id)
                == 1;
    }
}
