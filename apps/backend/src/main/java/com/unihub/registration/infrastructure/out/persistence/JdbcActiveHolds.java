package com.unihub.registration.infrastructure.out.persistence;

import com.unihub.registration.application.port.out.ActiveHolds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Counted by the partial index {@code idx_registrations_reserved_hold}. No transaction on purpose:
 * with none open, the routing datasource sends the statement to the Master.
 */
@Repository
class JdbcActiveHolds implements ActiveHolds {
    private final JdbcTemplate jdbc;

    JdbcActiveHolds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long count() {
        var count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM registrations WHERE status = 'RESERVED'", Long.class);
        return count == null ? 0 : count;
    }
}
