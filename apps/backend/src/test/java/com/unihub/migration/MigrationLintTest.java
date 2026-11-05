package com.unihub.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The static expand/contract check of INV-15 (M-58; Phase 7 checklist 4.1, D88; database-schema.md
 * §10): the newest migration may only expand the schema, unless it carries a contract-step comment.
 */
class MigrationLintTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static List<String> rules(String sql) {
        return MigrationLint.check(sql).stream().map(MigrationLint.Violation::rule).toList();
    }

    // ---------- the repository's own migrations ----------

    @Test
    @DisplayName("the newest migration of the repository only expands the schema")
    void theNewestMigration_passes() throws Exception {
        var newest = MigrationLint.newest(MIGRATIONS);

        assertThat(MigrationLint.check(newest))
                .as(
                        "%s must only expand the schema (or carry a contract-step comment)",
                        newest.getFileName())
                .isEmpty();
    }

    @Test
    @DisplayName("the newest migration is found by Flyway version order, not by file name")
    void newest_isByVersion(@TempDir Path dir) throws Exception {
        for (var name :
                List.of(
                        "V1__a.sql",
                        "V1_1__b.sql",
                        "V2__c.sql",
                        "V9__d.sql",
                        "V10__e.sql",
                        "notes.txt")) {
            Files.writeString(dir.resolve(name), "SELECT 1;");
        }

        assertThat(MigrationLint.newest(dir).getFileName().toString()).isEqualTo("V10__e.sql");
    }

    @Test
    @DisplayName(
            "a real contract-step-shaped migration is caught: V3 drops two columns of idempotency_keys")
    void aRealDropInHistory_isFlagged() throws Exception {
        var v3 =
                Files.list(MIGRATIONS)
                        .filter(f -> f.getFileName().toString().startsWith("V3__"))
                        .findFirst()
                        .orElseThrow();

        assertThat(MigrationLint.check(v3))
                .extracting(MigrationLint.Violation::rule)
                .contains("DROP COLUMN");
    }

    @Test
    @DisplayName("a fixture file with a DROP COLUMN and no explanation fails, at its line")
    void fixtureFile_failsAtTheDroppedColumn() throws Exception {
        var fixture = Path.of("src/test/resources/migration-lint/V99__drop_a_column.sql");

        var violations = MigrationLint.check(fixture);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).rule()).isEqualTo("DROP COLUMN");
        assertThat(violations.get(0).line()).isEqualTo(5);
    }

    // ---------- what is forbidden ----------

    static Stream<Arguments> forbidden() {
        return Stream.of(
                Arguments.of("DROP TABLE", "DROP TABLE workshops;"),
                Arguments.of("DROP TABLE", "drop  table\n  if exists workshops cascade;"),
                Arguments.of("DROP COLUMN", "ALTER TABLE workshops DROP COLUMN speaker_name;"),
                Arguments.of(
                        "DROP COLUMN", "ALTER TABLE workshops DROP COLUMN IF EXISTS speaker_name;"),
                Arguments.of("RENAME", "ALTER TABLE workshops RENAME COLUMN title TO name;"),
                Arguments.of("RENAME", "ALTER TABLE workshops RENAME TO sessions;"),
                Arguments.of(
                        "ALTER COLUMN TYPE",
                        "ALTER TABLE workshops ALTER COLUMN capacity TYPE BIGINT;"),
                Arguments.of(
                        "ALTER COLUMN TYPE",
                        "ALTER TABLE workshops ALTER capacity SET DATA TYPE BIGINT;"),
                Arguments.of(
                        "SET NOT NULL", "ALTER TABLE workshops ALTER COLUMN room SET NOT NULL;"),
                Arguments.of(
                        "ADD COLUMN NOT NULL without DEFAULT",
                        "ALTER TABLE workshops ADD COLUMN room TEXT NOT NULL;"),
                Arguments.of(
                        "ADD COLUMN NOT NULL without DEFAULT",
                        "ALTER TABLE workshops ADD room TEXT NOT NULL;"),
                Arguments.of(
                        "ADD COLUMN NOT NULL without DEFAULT",
                        "ALTER TABLE workshops ADD COLUMN ok TEXT, ADD COLUMN room TEXT NOT NULL;"),
                Arguments.of(
                        "DROP COLUMN",
                        "DO $$ BEGIN ALTER TABLE workshops DROP COLUMN speaker_name; END $$;"));
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @MethodSource("forbidden")
    @DisplayName("a statement an old pod cannot survive is reported")
    void forbiddenStatement_isReported(String rule, String sql) {
        assertThat(rules(sql)).contains(rule);
    }

    // ---------- what is allowed ----------

    static Stream<String> allowed() {
        return Stream.of(
                "CREATE TABLE sessions (id UUID PRIMARY KEY, title TEXT NOT NULL);",
                "ALTER TABLE workshops ADD COLUMN room TEXT;",
                "ALTER TABLE workshops ADD COLUMN room TEXT NOT NULL DEFAULT 'TBD';",
                "ALTER TABLE workshops ADD COLUMN room TEXT NOT NULL GENERATED ALWAYS AS (location) STORED;",
                "ALTER TABLE workshops ADD COLUMN IF NOT EXISTS room TEXT, ADD COLUMN floor INT;",
                "CREATE INDEX ix_workshops_room ON workshops (room);",
                "ALTER TABLE workshops ADD CONSTRAINT ck_room CHECK (room <> '');",
                "ALTER TABLE workshops ADD CONSTRAINT uq_room UNIQUE (room);",
                "ALTER TABLE check_in_scans ALTER COLUMN registration_id DROP NOT NULL;",
                "ALTER TABLE workshops DROP CONSTRAINT ck_old;",
                "INSERT INTO roles (id, name) VALUES (gen_random_uuid(), 'Auditor') ON CONFLICT DO NOTHING;",
                "-- DROP COLUMN speaker_name is the contract step of a later release\nSELECT 1;",
                "/* ALTER TABLE workshops DROP TABLE x */ SELECT 1;",
                "COMMENT ON COLUMN workshops.room IS 'never DROP COLUMN or RENAME this';");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allowed")
    @DisplayName(
            "an expanding statement, and prose or a string that merely mentions one, is not reported")
    void expandingStatement_isNotReported(String sql) {
        assertThat(MigrationLint.check(sql)).isEmpty();
    }

    // ---------- the contract step ----------

    @Test
    @DisplayName(
            "a contract-step comment that says which release stopped using the object lets it through")
    void contractStepComment_permitsTheDrop() {
        var sql =
                "-- contract-step: release 0.9 (Phase 7) no longer reads workshops.speaker_name\n"
                        + "ALTER TABLE workshops DROP COLUMN speaker_name;";

        assertThat(MigrationLint.check(sql)).isEmpty();
    }

    @Test
    @DisplayName(
            "the marker with nothing after it, or prose about expand and contract, is not an explanation")
    void emptyMarker_andProse_doNotCount() {
        assertThat(rules("-- contract-step:\nALTER TABLE workshops DROP COLUMN x;"))
                .contains("DROP COLUMN");
        assertThat(rules("-- contract-step: x\nALTER TABLE workshops DROP COLUMN x;"))
                .contains("DROP COLUMN");
        assertThat(
                        rules(
                                "-- later change must be expand -> migrate -> contract across releases\n"
                                        + "ALTER TABLE workshops DROP COLUMN x;"))
                .contains("DROP COLUMN");
    }

    @Test
    @DisplayName("every violation of a file is reported, with its line")
    void lines_areTrue() {
        var sql =
                "CREATE INDEX i ON t (a);\n"
                        + "\n"
                        + "ALTER TABLE t DROP COLUMN a;\n"
                        + "-- a comment\n"
                        + "ALTER TABLE t RENAME COLUMN b TO c;\n";

        var violations = MigrationLint.check(sql);

        assertThat(violations).extracting(MigrationLint.Violation::line).containsExactly(3, 5);
        assertThat(violations)
                .extracting(MigrationLint.Violation::rule)
                .containsExactly("DROP COLUMN", "RENAME");
    }
}
