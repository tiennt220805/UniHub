package com.unihub.shared.infrastructure.out.idempotency;

import com.unihub.shared.application.port.out.IdempotencyKeyCleanup;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * One {@code DELETE} bounded by its predicate, served by the partial index on {@code completed_at}.
 * Two concurrent runs find nothing new to delete the second time (distributed-coordination.md §2).
 */
@Repository
class JdbcIdempotencyKeyCleanup implements IdempotencyKeyCleanup {

    private static final String DELETE_EXPIRED =
            "DELETE FROM idempotency_keys WHERE status = 'COMPLETED' AND completed_at < ?";

    private final JdbcTemplate jdbc;

    JdbcIdempotencyKeyCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteCompletedBefore(Instant cutoff) {
        return jdbc.update(DELETE_EXPIRED, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }
}
