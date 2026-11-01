package com.unihub.checkin.infrastructure.out.persistence;

import com.unihub.checkin.application.port.out.AttendanceEntry;
import com.unihub.checkin.application.port.out.AttendancePage;
import com.unihub.checkin.application.port.out.AttendanceQueries;
import com.unihub.shared.domain.id.RegistrationId;
import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads of {@code attendances} by registration id. The table has no workshop column and Check-in
 * may not join another module's table, so the ids of the workshop's registrations come in as an
 * array and the database counts and pages by them (checkin.md §10).
 */
@Repository
class JdbcAttendanceQueries implements AttendanceQueries {
    private final JdbcTemplate jdbc;

    JdbcAttendanceQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countFor(Collection<RegistrationId> registrations) {
        if (registrations.isEmpty()) {
            return 0;
        }
        var ids = uuids(registrations);
        Long count =
                jdbc.query(
                        con -> {
                            var ps =
                                    con.prepareStatement(
                                            "SELECT count(*) FROM attendances"
                                                    + " WHERE registration_id = ANY (?)");
                            ps.setArray(1, array(con, ids));
                            return ps;
                        },
                        rs -> rs.next() ? rs.getLong(1) : 0L);
        return count == null ? 0 : count;
    }

    @Override
    public AttendancePage pageFor(Collection<RegistrationId> registrations, int page, int size) {
        var total = countFor(registrations);
        var totalPages = (int) ((total + size - 1) / size);
        if (total == 0) {
            return new AttendancePage(List.of(), page, size, 0, 0);
        }
        var ids = uuids(registrations);
        List<AttendanceEntry> items =
                jdbc.query(
                        con -> {
                            var ps =
                                    con.prepareStatement(
                                            "SELECT registration_id, checked_in_at FROM attendances"
                                                    + " WHERE registration_id = ANY (?)"
                                                    + " ORDER BY checked_in_at, registration_id"
                                                    + " LIMIT ? OFFSET ?");
                            ps.setArray(1, array(con, ids));
                            ps.setInt(2, size);
                            ps.setLong(3, (long) page * size);
                            return ps;
                        },
                        (rs, row) ->
                                new AttendanceEntry(
                                        RegistrationId.of(
                                                rs.getObject("registration_id", UUID.class)),
                                        rs.getObject("checked_in_at", OffsetDateTime.class)
                                                .toInstant()));
        return new AttendancePage(items, page, size, total, totalPages);
    }

    private static UUID[] uuids(Collection<RegistrationId> registrations) {
        return registrations.stream().map(RegistrationId::value).toArray(UUID[]::new);
    }

    private static Array array(java.sql.Connection con, UUID[] ids) throws java.sql.SQLException {
        return con.createArrayOf("uuid", ids);
    }
}
