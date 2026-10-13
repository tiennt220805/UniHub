package com.unihub.user.infrastructure.out.persistence;

import com.unihub.user.application.port.out.LegacyStudentAccounts;
import com.unihub.user.application.port.out.LegacyStudentRecord;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The upsert of user.md §8, sent as one statement per batch: the batch's columns travel as three
 * arrays and {@code unnest} turns them back into rows. Each row is still its own atomic
 * insert-or-update on {@code legacy_student_id}, so a concurrent duplicate run converges.
 */
@Repository
class JdbcLegacyStudentAccounts implements LegacyStudentAccounts {

    private static final String HOLDERS_OF_EMAILS =
            "SELECT email, legacy_student_id FROM users WHERE email = ANY (?)";

    // password_hash and status are deliberately absent from DO UPDATE (user.md §8). The WHERE
    // skips rows that did not change, so they return nothing. (xmax = 0) is true only for a row
    // this statement inserted.
    private static final String UPSERT =
            "INSERT INTO users (email, full_name, legacy_student_id, status)"
                    + " SELECT e, n, l, 'ACTIVE'"
                    + " FROM unnest(?::varchar[], ?::varchar[], ?::varchar[]) AS t (e, n, l)"
                    + " ON CONFLICT (legacy_student_id) DO UPDATE"
                    + " SET email = EXCLUDED.email, full_name = EXCLUDED.full_name,"
                    + " updated_at = now()"
                    + " WHERE users.email <> EXCLUDED.email OR users.full_name <> EXCLUDED.full_name"
                    + " RETURNING (xmax = 0) AS inserted";

    private final JdbcTemplate jdbcTemplate;

    JdbcLegacyStudentAccounts(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Optional<String>> holdersOfEmails(Collection<String> emails) {
        var holders = new HashMap<String, Optional<String>>();
        if (emails.isEmpty()) {
            return holders;
        }
        jdbcTemplate.query(
                con -> {
                    var ps = con.prepareStatement(HOLDERS_OF_EMAILS);
                    ps.setArray(1, con.createArrayOf("varchar", emails.toArray()));
                    return ps;
                },
                rs -> {
                    holders.put(
                            rs.getString("email"),
                            Optional.ofNullable(rs.getString("legacy_student_id")));
                });
        return holders;
    }

    @Override
    public UpsertOutcome upsert(List<LegacyStudentRecord> students) {
        if (students.isEmpty()) {
            return new UpsertOutcome(0, 0);
        }
        var counts = new int[2];
        jdbcTemplate.query(
                con -> {
                    var ps = con.prepareStatement(UPSERT);
                    ps.setArray(1, varcharArray(con, students, LegacyStudentRecord::email));
                    ps.setArray(2, varcharArray(con, students, LegacyStudentRecord::fullName));
                    ps.setArray(
                            3, varcharArray(con, students, LegacyStudentRecord::legacyStudentId));
                    return ps;
                },
                rs -> {
                    counts[rs.getBoolean("inserted") ? 0 : 1]++;
                });
        return new UpsertOutcome(counts[0], counts[1]);
    }

    private static Array varcharArray(
            Connection con,
            List<LegacyStudentRecord> students,
            Function<LegacyStudentRecord, String> column)
            throws SQLException {
        return con.createArrayOf("varchar", students.stream().map(column).toArray());
    }
}
