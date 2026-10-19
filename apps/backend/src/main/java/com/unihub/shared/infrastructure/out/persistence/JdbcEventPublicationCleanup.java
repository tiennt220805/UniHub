package com.unihub.shared.infrastructure.out.persistence;

import com.unihub.shared.application.port.out.EventPublicationCleanup;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEventPublicationCleanup implements EventPublicationCleanup {

    private static final String DELETE_PUBLISHED =
            "DELETE FROM event_publications WHERE status = 'PUBLISHED' AND published_at < ?";

    private final JdbcTemplate jdbc;

    JdbcEventPublicationCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.update(DELETE_PUBLISHED, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }
}
