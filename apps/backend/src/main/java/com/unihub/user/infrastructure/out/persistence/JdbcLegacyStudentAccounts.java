package com.unihub.user.infrastructure.out.persistence;

import com.unihub.user.application.port.out.LegacyStudentAccounts;
import com.unihub.user.application.port.out.LegacyStudentRecord;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The upsert of user.md §8, one row at a time and each row inside its own SAVEPOINT. Each row is an
 * atomic insert-or-update on {@code legacy_student_id}, so a concurrent duplicate run converges —
 * except that {@code ON CONFLICT} absorbs a race only on its own arbiter. When a second run inserts
 * the same new student at the same instant, the index on {@code email} can refuse the loser
 * instead. That row is valid (the other run has just written it), so the savepoint undoes only it
 * and the statement runs once more, this time through the {@code ON CONFLICT} path (checklist D28).
 * A second refusal is a different account holding the email, a genuine data conflict: it propagates
 * and fails the run, as before.
 */
@Repository
class JdbcLegacyStudentAccounts implements LegacyStudentAccounts {

    private static final String HOLDERS_OF_EMAILS =
            "SELECT email, legacy_student_id FROM users WHERE email = ANY (?)";

    // password_hash and status are deliberately absent from DO UPDATE (user.md §8). The WHERE
    // skips a row that did not change, so it returns nothing. (xmax = 0) is true only for a row
    // this statement inserted.

    /** The unique index on {@code users.email} (V1: the implicit name of an inline UNIQUE). */
    static final String EMAIL_CONSTRAINT = "users_email_key";

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String UPSERT =
            "INSERT INTO users (email, full_name, legacy_student_id, status)"
                    + " VALUES (?, ?, ?, 'ACTIVE')"
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
        return jdbcTemplate.execute(
                (Connection con) -> {
                    int created = 0;
                    int updated = 0;
                    for (var student : students) {
                        switch (upsertRow(con, student)) {
                            case CREATED -> created++;
                            case UPDATED -> updated++;
                            case UNCHANGED -> {}
                        }
                    }
                    return new UpsertOutcome(created, updated);
                });
    }

    private enum RowOutcome {
        CREATED,
        UPDATED,
        UNCHANGED
    }

    private static RowOutcome upsertRow(Connection con, LegacyStudentRecord student)
            throws SQLException {
        var savepoint = con.setSavepoint();
        try {
            try {
                return executeUpsert(con, student);
            } catch (SQLException refused) {
                if (!isEmailIndexViolation(refused)) {
                    throw refused;
                }
                // only this row is undone; the batch transaction survives
                con.rollback(savepoint);
                return executeUpsert(con, student);
            }
        } finally {
            releaseQuietly(con, savepoint);
        }
    }

    private static RowOutcome executeUpsert(Connection con, LegacyStudentRecord student)
            throws SQLException {
        try (var ps = con.prepareStatement(UPSERT)) {
            ps.setString(1, student.email());
            ps.setString(2, student.fullName());
            ps.setString(3, student.legacyStudentId());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return RowOutcome.UNCHANGED;
                }
                return rs.getBoolean("inserted") ? RowOutcome.CREATED : RowOutcome.UPDATED;
            }
        }
    }

    /** By constraint name from the server's error, never by message text. */
    static boolean isEmailIndexViolation(SQLException e) {
        return UNIQUE_VIOLATION.equals(e.getSQLState())
                && e instanceof PSQLException postgres
                && postgres.getServerErrorMessage() != null
                && EMAIL_CONSTRAINT.equals(postgres.getServerErrorMessage().getConstraint());
    }

    /** A savepoint after a rollback to it, or after the transaction ended, is already gone. */
    private static void releaseQuietly(Connection con, Savepoint savepoint) {
        try {
            con.releaseSavepoint(savepoint);
        } catch (SQLException alreadyGone) {
            // nothing left to release; the outcome of the row is already decided
        }
    }
}
