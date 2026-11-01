package com.unihub.checkin.infrastructure.out.persistence;

import com.unihub.checkin.application.port.out.Attendances;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence of {@code attendances} (database-schema.md §6.2). */
@Repository
class JdbcAttendances implements Attendances {

    private static final String UPSERT =
            "INSERT INTO attendances (registration_id, winning_scan_id, checked_in_at)"
                    + " VALUES (?, ?, ?) ON CONFLICT (registration_id) DO UPDATE"
                    + " SET winning_scan_id = excluded.winning_scan_id,"
                    + " checked_in_at = excluded.checked_in_at, updated_at = now()"
                    + " WHERE attendances.winning_scan_id <> excluded.winning_scan_id";

    private final JdbcTemplate jdbc;

    JdbcAttendances(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(RegistrationId registration, ScanId winner, Instant checkedInAt) {
        jdbc.update(
                UPSERT,
                registration.value(),
                winner.value(),
                OffsetDateTime.ofInstant(checkedInAt, ZoneOffset.UTC));
    }
}
