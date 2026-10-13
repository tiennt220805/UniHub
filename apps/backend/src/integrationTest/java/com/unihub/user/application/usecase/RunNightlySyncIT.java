package com.unihub.user.application.usecase;

import static com.unihub.user.infrastructure.out.persistence.UserRowBuilder.aLegacyStudent;
import static com.unihub.user.infrastructure.out.persistence.UserRowBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.user.application.port.in.RunNightlySyncUseCase;
import com.unihub.user.application.result.RunNightlySyncResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-09 and M-10 (INV-13) end to end: export file on disk → use case → JDBC adapters → real
 * Postgres. Batch size is 2 so every file spans several committed batches. Rows are committed, so
 * each test deletes what it created; ids and emails carry a per-test prefix so nothing collides
 * with other ITs.
 */
@SpringBootTest
class RunNightlySyncIT extends IntegrationTestSupport {

    /** 20:00 UTC on the 12th is the 13th in Asia/Ho_Chi_Minh, so tonight's file is the 13th's. */
    private static final Instant NOW = Instant.parse("2026-10-12T20:00:00Z");

    private static final String EXPORT_FILE = "students_export_20261013.csv";
    private static final String HEADER = "legacy_student_id,email,full_name";
    private static final Path INBOUND = createInboundDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.jobs.nightly-sync.inbound-dir", INBOUND::toString);
        registry.add("unihub.jobs.nightly-sync.batch-size", () -> "2");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private RunNightlySyncUseCase sync;
    @Autowired private JdbcTemplate jdbc;

    /** Unique per test instance: keeps legacy ids and emails clear of every other test's. */
    private final String p = "IT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

    private final List<UUID> runs = new ArrayList<>();

    private static Path createInboundDir() {
        try {
            return Files.createTempDirectory("legacy-sync-inbound");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterEach
    void cleanUp() throws IOException {
        Files.deleteIfExists(INBOUND.resolve(EXPORT_FILE));
        for (var run : runs) {
            jdbc.update("DELETE FROM legacy_sync_errors WHERE sync_run_id = ?", run);
            jdbc.update("DELETE FROM legacy_sync_runs WHERE id = ?", run);
        }
        jdbc.update(
                "DELETE FROM users WHERE legacy_student_id LIKE ? OR email LIKE ?",
                p + "%",
                p.toLowerCase() + "%");
    }

    private String id(int n) {
        return p + String.format("%03d", n);
    }

    private String email(int n) {
        return p.toLowerCase() + "-" + n + "@uni.test";
    }

    private void writeExport(String... lines) throws IOException {
        Files.writeString(
                INBOUND.resolve(EXPORT_FILE),
                String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    private RunNightlySyncResult runSync() {
        var result = sync.run();
        runs.add(result.runId());
        return result;
    }

    private Map<String, Object> runRow(UUID runId) {
        return jdbc.queryForMap("SELECT * FROM legacy_sync_runs WHERE id = ?", runId);
    }

    private Map<String, Object> userRow(String legacyStudentId) {
        return jdbc.queryForMap(
                "SELECT email, full_name, password_hash, status FROM users"
                        + " WHERE legacy_student_id = ?",
                legacyStudentId);
    }

    private int syncedUserCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE legacy_student_id LIKE ?",
                Integer.class,
                p + "%");
    }

    private int errorCount(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM legacy_sync_errors WHERE sync_run_id = ?",
                Integer.class,
                runId);
    }

    // ---------- M-09: the validate-then-write pipeline ----------

    @Test
    @DisplayName(
            "M-09: new students are created, changed ones refreshed, unchanged ones left alone;"
                    + " the run is COMPLETED with its counters")
    void m09_validExport_upsertsAndCompletes() throws IOException {
        aLegacyStudent()
                .withLegacyStudentId(id(1))
                .withEmail(email(1))
                .withFullName("Old Name")
                .withPasswordHash("{bcrypt}student-chose-this")
                .withStatus("SUSPENDED")
                .insert(jdbc);
        aLegacyStudent()
                .withLegacyStudentId(id(2))
                .withEmail(email(2))
                .withFullName("Unchanged")
                .insert(jdbc);
        writeExport(
                HEADER,
                id(1) + "," + email(1) + ",New Name",
                id(2) + "," + email(2) + ",Unchanged",
                id(3) + "," + email(3) + ",\"Trần, Thị C\"");

        var result = runSync();

        assertThat(result)
                .isEqualTo(
                        new RunNightlySyncResult.Completed(
                                result.runId(), result.exportDate(), 3, 1, 1, 0));
        var refreshed = userRow(id(1));
        assertThat(refreshed.get("full_name")).isEqualTo("New Name");
        assertThat(refreshed.get("password_hash")).isEqualTo("{bcrypt}student-chose-this");
        assertThat(refreshed.get("status")).isEqualTo("SUSPENDED");
        var created = userRow(id(3));
        assertThat(created.get("email")).isEqualTo(email(3));
        assertThat(created.get("full_name")).isEqualTo("Trần, Thị C");
        assertThat(created.get("password_hash")).isNull();
        assertThat(created.get("status")).isEqualTo("ACTIVE");

        var run = runRow(result.runId());
        assertThat(run.get("export_date").toString()).isEqualTo("2026-10-13");
        assertThat(run.get("status")).isEqualTo("COMPLETED");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(run)
                .containsEntry("rows_processed", 3)
                .containsEntry("rows_created", 1)
                .containsEntry("rows_updated", 1)
                .containsEntry("rows_quarantined", 0);
        assertThat(errorCount(result.runId())).isZero();
    }

    @Test
    @DisplayName("M-09: running the same file again converges — nothing created, nothing changed")
    void m09_rerun_isIdempotent() throws IOException {
        writeExport(HEADER, id(1) + "," + email(1) + ",A", id(2) + "," + email(2) + ",B");
        runSync();

        var again = runSync();

        assertThat(again)
                .isEqualTo(
                        new RunNightlySyncResult.Completed(
                                again.runId(), again.exportDate(), 2, 0, 0, 0));
        assertThat(syncedUserCount()).isEqualTo(2);
    }

    /**
     * user.md §8's lease-overrun case. The data always converges: no duplicate, no lost row, and
     * neither run is left RUNNING. Whether the slower run COMPLETES is not asserted: ON CONFLICT
     * only absorbs a race on its arbiter (legacy_student_id), so two runs inserting the same new
     * student at once can still fail the slower one on UNIQUE (email), contrary to user.md §8.
     * Phase 1 cannot hit this (one instance, in-process job lock); it is open for Phase 3 (INV-09).
     */
    @Test
    @DisplayName("M-09: two runs over the same file at once leave no duplicate and no lost row")
    void m09_concurrentRuns_dataConverges() throws IOException {
        var lines = new ArrayList<String>(List.of(HEADER));
        for (int n = 1; n <= 20; n++) {
            lines.add(id(n) + "," + email(n) + ",Student " + n);
        }
        writeExport(lines.toArray(String[]::new));

        var first = CompletableFuture.supplyAsync(sync::run);
        var second = CompletableFuture.supplyAsync(sync::run);
        var results = List.of(first.join(), second.join());
        results.forEach(r -> runs.add(r.runId()));

        assertThat(results).anyMatch(r -> r instanceof RunNightlySyncResult.Completed);
        assertThat(results)
                .allSatisfy(
                        r -> assertThat(runRow(r.runId()).get("status")).isNotEqualTo("RUNNING"));
        assertThat(syncedUserCount()).isEqualTo(20);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT email) FROM users WHERE legacy_student_id LIKE ?",
                                Integer.class,
                                p + "%"))
                .isEqualTo(20);
        assertThat(userRow(id(20)).get("full_name")).isEqualTo("Student 20");
    }

    // ---------- M-10: the error store ----------

    @Test
    @DisplayName(
            "M-10: malformed, invalid and duplicate rows land in legacy_sync_errors with their"
                    + " reason; the batch still completes and applies every good row")
    void m10_badRecords_quarantined_batchCompletes() throws IOException {
        anActiveUser().withEmail(email(90)).insert(jdbc);
        writeExport(
                HEADER,
                id(1) + "," + email(1) + ",Good One",
                id(2) + "," + email(2),
                id(3) + ",not-an-email,Bad Email",
                id(4) + "," + email(4) + ",",
                id(5) + "," + email(5) + ",Dup Id First",
                id(6) + "," + email(6) + ",Good Two",
                id(5) + "," + email(7) + ",Dup Id Second",
                id(8) + ",same-" + email(8) + ",Dup Email First",
                id(9) + ",same-" + email(8) + ",Dup Email Second",
                id(10) + "," + email(90) + ",Email Taken",
                id(11) + ",\"" + email(11) + ",Unterminated");

        var result = runSync();

        assertThat(result)
                .isEqualTo(
                        new RunNightlySyncResult.Completed(
                                result.runId(), result.exportDate(), 11, 2, 0, 9));
        assertThat(
                        jdbc.queryForList(
                                "SELECT legacy_student_id FROM users WHERE legacy_student_id LIKE ?"
                                        + " ORDER BY legacy_student_id",
                                String.class,
                                p + "%"))
                .containsExactly(id(1), id(6));

        var errors =
                jdbc.queryForList(
                        "SELECT row_number, raw_row, reason_code, reviewed FROM legacy_sync_errors"
                                + " WHERE sync_run_id = ? ORDER BY row_number",
                        result.runId());
        assertThat(errors)
                .extracting(e -> e.get("row_number"), e -> e.get("reason_code"))
                .containsExactly(
                        tuple(3, "MALFORMED_ROW"),
                        tuple(4, "INVALID_FIELD"),
                        tuple(5, "INVALID_FIELD"),
                        tuple(6, "DUPLICATE_IN_FILE"),
                        tuple(8, "DUPLICATE_IN_FILE"),
                        tuple(9, "DUPLICATE_IN_FILE"),
                        tuple(10, "DUPLICATE_IN_FILE"),
                        tuple(11, "INVALID_FIELD"),
                        tuple(12, "MALFORMED_ROW"));
        assertThat(errors.get(0).get("raw_row")).isEqualTo(id(2) + "," + email(2));
        assertThat(errors).allSatisfy(e -> assertThat(e.get("reviewed")).isEqualTo(false));

        var run = runRow(result.runId());
        assertThat(run.get("status")).isEqualTo("COMPLETED");
        assertThat(run)
                .containsEntry("rows_processed", 11)
                .containsEntry("rows_created", 2)
                .containsEntry("rows_quarantined", 9);
    }

    // ---------- whole-file failures (user.md §2) ----------

    @Test
    @DisplayName("no file for tonight: one FAILED / FILE_NOT_FOUND run row, nothing else written")
    void missingFile_failedRunRecorded() {
        var result = runSync();

        var run = runRow(result.runId());
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("failure_reason")).isEqualTo("FILE_NOT_FOUND");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(errorCount(result.runId())).isZero();
    }

    @Test
    @DisplayName("bytes that are not UTF-8: FAILED / FILE_UNREADABLE, zero users and zero errors")
    void unreadableFile_nothingApplied() throws IOException {
        var good =
                (HEADER + "\n" + id(1) + "," + email(1) + ",A\n").getBytes(StandardCharsets.UTF_8);
        var bytes = new byte[good.length + 2];
        System.arraycopy(good, 0, bytes, 0, good.length);
        bytes[good.length] = (byte) 0xC3;
        bytes[good.length + 1] = (byte) 0x28;
        Files.write(INBOUND.resolve(EXPORT_FILE), bytes);

        var result = runSync();

        var run = runRow(result.runId());
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("failure_reason")).isEqualTo("FILE_UNREADABLE");
        assertThat(run.get("failure_detail")).isEqualTo("file is not valid UTF-8");
        assertThat(syncedUserCount()).isZero();
        assertThat(errorCount(result.runId())).isZero();
    }
}
