package com.unihub.user.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import com.unihub.user.application.port.out.LegacyStudentAccounts.UpsertOutcome;
import com.unihub.user.application.port.out.LegacyStudentRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * The upsert of user.md §8 against the real {@code users} table, in a transaction that is rolled
 * back (checklist D28, AC28.1, AC28.2).
 *
 * <p>The race that makes the e-mail index refuse a valid row needs two transactions to interleave
 * inside one statement, which no test can arrange on demand ({@code
 * RunNightlySyncIT#m09_concurrentRuns_dataConverges} meets it by repetition). So the refusal is
 * injected: a trigger raises exactly the error PostgreSQL raises (SQLSTATE 23505, constraint {@code
 * users_email_key}) on a chosen student. What this proves is the part that is ours — undo only that
 * row, retry it once, keep every other row — and that nothing else is retried. The trigger and its
 * sequence are DDL in the test's transaction and vanish with the rollback.
 */
class JdbcLegacyStudentAccountsIT extends RolledBackJdbcSupport {

    private final String p = "T" + UUID.randomUUID().toString().substring(0, 6);
    private JdbcLegacyStudentAccounts accounts;

    @BeforeEach
    void adapter() {
        accounts = new JdbcLegacyStudentAccounts(jdbc);
    }

    private LegacyStudentRecord student(int n) {
        return new LegacyStudentRecord(p + n, p + n + "@uni.test", "Student " + n);
    }

    private void refuseOnInsert(int n, int timesToRefuse, String constraint) {
        jdbc.execute("CREATE SEQUENCE refusals");
        jdbc.execute(
                "CREATE FUNCTION refuse() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " IF NEW.legacy_student_id = '"
                        + p
                        + n
                        + "' AND nextval('refusals') <= "
                        + timesToRefuse
                        + " THEN RAISE EXCEPTION 'injected' USING ERRCODE = '23505',"
                        + " CONSTRAINT = '"
                        + constraint
                        + "'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER refuse BEFORE INSERT ON users"
                        + " FOR EACH ROW EXECUTE FUNCTION refuse()");
    }

    private long refusalsSoFar() {
        return jdbc.queryForObject("SELECT last_value FROM refusals", Long.class);
    }

    private int storedAmong(int from, int to) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE legacy_student_id = ANY (?)",
                Integer.class,
                (Object)
                        java.util.stream.IntStream.rangeClosed(from, to)
                                .mapToObj(n -> p + n)
                                .toArray(String[]::new));
    }

    @Test
    @DisplayName("a row refused once by the e-mail index is retried once; the other rows commit")
    void emailRefusal_isRetriedOnce_otherRowsKept() {
        refuseOnInsert(3, 1, "users_email_key");

        var outcome = accounts.upsert(List.of(student(1), student(2), student(3), student(4)));

        assertThat(outcome).isEqualTo(new UpsertOutcome(4, 0));
        assertThat(storedAmong(1, 4)).isEqualTo(4);
        assertThat(refusalsSoFar()).as("student 3 was tried twice, no more").isEqualTo(2);
    }

    @Test
    @DisplayName("a row refused twice fails the call: the retry happens once, never in a loop")
    void secondRefusal_propagates() {
        refuseOnInsert(2, Integer.MAX_VALUE, "users_email_key");
        jdbc.execute("SAVEPOINT before_upsert");

        assertThatThrownBy(() -> accounts.upsert(List.of(student(1), student(2), student(3))))
                .isInstanceOf(DuplicateKeyException.class);

        jdbc.execute("ROLLBACK TO SAVEPOINT before_upsert");
        assertThat(refusalsSoFar()).as("one attempt and one retry").isEqualTo(2);
    }

    @Test
    @DisplayName("a unique violation of any other index is not retried")
    void otherConstraint_isNotRetried() {
        refuseOnInsert(2, Integer.MAX_VALUE, "users_legacy_student_id_key");
        jdbc.execute("SAVEPOINT before_upsert");

        assertThatThrownBy(() -> accounts.upsert(List.of(student(1), student(2))))
                .isInstanceOf(DuplicateKeyException.class);

        jdbc.execute("ROLLBACK TO SAVEPOINT before_upsert");
        assertThat(refusalsSoFar()).as("a single attempt").isEqualTo(1);
    }

    @Test
    @DisplayName("an e-mail that another account holds is a real conflict and fails the call")
    void emailHeldByAnotherAccount_fails() {
        jdbc.update(
                "INSERT INTO users (email, full_name, status) VALUES (?, 'Staff', 'ACTIVE')",
                p + "9@uni.test");

        assertThatThrownBy(
                        () ->
                                accounts.upsert(
                                        List.of(
                                                new LegacyStudentRecord(
                                                        p + "9", p + "9@uni.test", "Student 9"))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("counts what was inserted, what changed and what was already up to date")
    void outcome_countsCreatedUpdatedUnchanged() {
        assertThat(accounts.upsert(List.of(student(1), student(2))))
                .isEqualTo(new UpsertOutcome(2, 0));

        assertThat(accounts.upsert(List.of(student(1), student(2))))
                .as("nothing changed")
                .isEqualTo(new UpsertOutcome(0, 0));

        var renamed = new LegacyStudentRecord(p + 2, p + "2@uni.test", "Renamed");
        assertThat(accounts.upsert(List.of(student(1), renamed)))
                .isEqualTo(new UpsertOutcome(0, 1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT full_name FROM users WHERE legacy_student_id = ?",
                                String.class,
                                p + 2))
                .isEqualTo("Renamed");
    }

    @Test
    @DisplayName("an empty batch is a no-op")
    void emptyBatch() {
        assertThat(accounts.upsert(List.of())).isEqualTo(new UpsertOutcome(0, 0));
    }
}
