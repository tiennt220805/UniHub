package com.unihub.registration.infrastructure.out.persistence;

import com.unihub.registration.api.RegistrationCounts;
import com.unihub.registration.application.port.out.RegistrationQueries;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The report's registration side, counted by the database (checkin.md §10). */
@Repository
class JdbcRegistrationQueries implements RegistrationQueries {
    private final JdbcTemplate jdbc;

    JdbcRegistrationQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RegistrationCounts countsOf(WorkshopId workshopId) {
        return jdbc.queryForObject(
                "SELECT count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,"
                        + " count(*) FILTER (WHERE status = 'RESERVED') AS reserved"
                        + " FROM registrations WHERE workshop_id = ?",
                (rs, row) ->
                        new RegistrationCounts(rs.getLong("confirmed"), rs.getLong("reserved")),
                workshopId.value());
    }

    @Override
    public List<RegistrationId> idsOf(WorkshopId workshopId) {
        return jdbc
                .queryForList(
                        "SELECT id FROM registrations WHERE workshop_id = ? ORDER BY id",
                        UUID.class,
                        workshopId.value())
                .stream()
                .map(RegistrationId::new)
                .toList();
    }
}
