package com.unihub.user.application.usecase;

import static com.unihub.user.application.usecase.ExportLines.HEADER;
import static com.unihub.user.application.usecase.ExportLines.numbered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.user.application.port.out.ExportLine;
import com.unihub.user.application.port.out.LegacyStudentAccounts;
import com.unihub.user.application.port.out.LegacyStudentAccounts.UpsertOutcome;
import com.unihub.user.application.port.out.LegacyStudentRecord;
import com.unihub.user.application.port.out.LegacySyncErrors;
import com.unihub.user.application.port.out.LegacySyncRuns;
import com.unihub.user.application.port.out.QuarantineReason;
import com.unihub.user.application.port.out.QuarantinedRow;
import com.unihub.user.application.port.out.StudentExport;
import com.unihub.user.application.port.out.StudentExportSource;
import com.unihub.user.application.port.out.StudentExportUnreadableException;
import com.unihub.user.application.port.out.SyncFailureReason;
import com.unihub.user.application.port.out.SyncMetrics;
import com.unihub.user.application.port.out.SyncProgress;
import com.unihub.user.application.result.RunNightlySyncResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** M-09/M-10 (INV-13) at unit level: user.md §2–§5 with every port mocked. */
class RunNightlySyncTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** 20:00 UTC on the 12th is 03:00 on the 13th in Asia/Ho_Chi_Minh. */
    private static final Instant NOW = Instant.parse("2026-10-12T20:00:00Z");

    private static final LocalDate EXPORT_DATE = LocalDate.of(2026, 10, 13);
    private static final UUID RUN = UUID.randomUUID();

    private final StudentExportSource exports = mock(StudentExportSource.class);
    private final LegacySyncRuns runs = mock(LegacySyncRuns.class);
    private final LegacyStudentAccounts accounts = mock(LegacyStudentAccounts.class);
    private final LegacySyncErrors errors = mock(LegacySyncErrors.class);
    private final SyncMetrics syncMetrics = mock(SyncMetrics.class);

    private final List<LegacyStudentRecord> upserted = new ArrayList<>();
    private final List<QuarantinedRow> quarantined = new ArrayList<>();

    @BeforeEach
    void stubPorts() {
        when(runs.start(EXPORT_DATE)).thenReturn(RUN);
        when(accounts.holdersOfEmails(any())).thenReturn(Map.of());
        when(accounts.upsert(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<LegacyStudentRecord> batch = invocation.getArgument(0);
                            upserted.addAll(batch);
                            return new UpsertOutcome(batch.size(), 0);
                        });
        doAnswer(
                        invocation -> {
                            List<QuarantinedRow> rows = invocation.getArgument(1);
                            quarantined.addAll(rows);
                            return null;
                        })
                .when(errors)
                .quarantine(eq(RUN), anyList());
    }

    private RunNightlySync useCase(int batchSize) {
        return new RunNightlySync(
                exports,
                runs,
                new NightlySyncBatchTransaction(accounts, errors, runs),
                new NightlySyncPolicy(ZONE, batchSize),
                syncMetrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenExport(List<ExportLine> lines) {
        StudentExport export = lines::stream;
        when(exports.find(EXPORT_DATE)).thenReturn(Optional.of(export));
    }

    private void assertNothingWritten() {
        verify(accounts, never()).upsert(anyList());
        verify(errors, never()).quarantine(any(), anyList());
        verify(runs, never()).recordProgress(any(), any());
        verify(runs, never()).complete(any());
    }

    // ---------- whole-file outcomes (user.md §2) ----------

    @Test
    @DisplayName("names tonight's export by the date in Asia/Ho_Chi_Minh, not UTC")
    void exportDate_isTheLocalDate() {
        when(exports.find(EXPORT_DATE)).thenReturn(Optional.empty());

        var result = useCase(500).run();

        verify(runs).start(EXPORT_DATE);
        assertThat(result.exportDate()).isEqualTo(EXPORT_DATE);
    }

    @Test
    @DisplayName("no file: the run is recorded FAILED / FILE_NOT_FOUND and nothing is written")
    void missingFile() {
        when(exports.find(EXPORT_DATE)).thenReturn(Optional.empty());

        var result = useCase(500).run();

        assertThat(result)
                .isEqualTo(
                        new RunNightlySyncResult.Failed(RUN, EXPORT_DATE, "FILE_NOT_FOUND", null));
        verify(runs).fail(RUN, SyncFailureReason.FILE_NOT_FOUND, null);
        verify(syncMetrics, never()).runCompleted(anyInt(), anyInt());
        assertNothingWritten();
    }

    @Test
    @DisplayName("empty file: FAILED / FILE_UNREADABLE, nothing written")
    void emptyFile() {
        givenExport(List.of());

        var result = useCase(500).run();

        assertThat(result)
                .isEqualTo(
                        new RunNightlySyncResult.Failed(
                                RUN,
                                EXPORT_DATE,
                                "FILE_UNREADABLE",
                                "file is empty: no header row"));
        verify(runs).fail(RUN, SyncFailureReason.FILE_UNREADABLE, "file is empty: no header row");
        assertNothingWritten();
    }

    @Test
    @DisplayName("unusable header: FAILED / FILE_UNREADABLE, nothing written")
    void unusableHeader() {
        givenExport(numbered("legacy_student_id,email", "SV001,a@uni.edu"));

        useCase(500).run();

        verify(runs)
                .fail(
                        RUN,
                        SyncFailureReason.FILE_UNREADABLE,
                        "header lacks required column 'full_name'");
        assertNothingWritten();
    }

    @Test
    @DisplayName("undecodable bytes late in the file: FAILED / FILE_UNREADABLE, zero rows applied")
    void undecodableBytes_noPartialApply() {
        var good = numbered(HEADER, "SV001,a@uni.edu,A", "SV002,b@uni.edu,B");
        StudentExport export =
                () ->
                        StreamSupport.stream(
                                Spliterators.spliteratorUnknownSize(
                                        failingAfter(good, "file is not valid UTF-8"), 0),
                                false);
        when(exports.find(EXPORT_DATE)).thenReturn(Optional.of(export));

        useCase(1).run();

        verify(runs).fail(RUN, SyncFailureReason.FILE_UNREADABLE, "file is not valid UTF-8");
        assertNothingWritten();
    }

    private static Iterator<ExportLine> failingAfter(List<ExportLine> lines, String message) {
        var delegate = lines.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public ExportLine next() {
                if (delegate.hasNext()) {
                    return delegate.next();
                }
                throw new StudentExportUnreadableException(message, null);
            }
        };
    }

    // ---------- the pipeline (user.md §3–§4) ----------

    @Test
    @DisplayName("valid rows are upserted, the run counts them and completes")
    void validRows_completed() {
        givenExport(numbered(HEADER, "SV001,a@uni.edu,Nguyen Van A", "SV002,b@uni.edu,Tran Thi B"));

        var result = useCase(500).run();

        assertThat(result)
                .isEqualTo(new RunNightlySyncResult.Completed(RUN, EXPORT_DATE, 2, 2, 0, 0));
        assertThat(upserted)
                .containsExactly(
                        new LegacyStudentRecord("SV001", "a@uni.edu", "Nguyen Van A"),
                        new LegacyStudentRecord("SV002", "b@uni.edu", "Tran Thi B"));
        assertThat(quarantined).isEmpty();
        InOrder order = Mockito.inOrder(runs);
        order.verify(runs).start(EXPORT_DATE);
        order.verify(runs).recordProgress(RUN, new SyncProgress(2, 2, 0, 0));
        order.verify(runs).complete(RUN);
        verify(runs, never()).fail(any(), any(), any());
    }

    @Test
    @DisplayName("bad rows are quarantined with their reason; the good rows still go through")
    void badRows_quarantined_batchContinues() {
        givenExport(
                numbered(
                        HEADER,
                        "SV001,a@uni.edu,Good One",
                        "SV002,b@uni.edu",
                        ",c@uni.edu,No Id",
                        "SV004,not-an-email,Bad Email",
                        "SV005,e@uni.edu,",
                        "SV006,,No Email",
                        "X".repeat(51) + ",f@uni.edu,Long Id",
                        "SV008,h@uni.edu,Good Two"));

        var result = useCase(500).run();

        assertThat(quarantined)
                .extracting(
                        QuarantinedRow::rowNumber,
                        QuarantinedRow::reason,
                        QuarantinedRow::reasonDetail)
                .containsExactly(
                        tuple(3, QuarantineReason.MALFORMED_ROW, "expected 3 columns, found 2"),
                        tuple(4, QuarantineReason.INVALID_FIELD, "legacy_student_id is empty"),
                        tuple(5, QuarantineReason.INVALID_FIELD, "email is not a valid address"),
                        tuple(6, QuarantineReason.INVALID_FIELD, "full_name is empty"),
                        tuple(7, QuarantineReason.INVALID_FIELD, "email is empty"),
                        tuple(
                                8,
                                QuarantineReason.INVALID_FIELD,
                                "legacy_student_id is longer than 50"));
        assertThat(quarantined.get(0).rawRow()).isEqualTo("SV002,b@uni.edu");
        assertThat(upserted)
                .extracting(LegacyStudentRecord::legacyStudentId)
                .containsExactly("SV001", "SV008");
        assertThat(result)
                .isEqualTo(new RunNightlySyncResult.Completed(RUN, EXPORT_DATE, 8, 2, 0, 6));
        verify(runs).complete(RUN);
        verify(syncMetrics).runCompleted(8, 6);
    }

    @Test
    @DisplayName("a repeated legacy_student_id is rejected on every occurrence, the first included")
    void duplicateLegacyId_everyOccurrenceRejected() {
        givenExport(
                numbered(
                        HEADER,
                        "SV001,a@uni.edu,First",
                        "SV002,b@uni.edu,Unique",
                        "SV001,c@uni.edu,Second"));

        useCase(1).run();

        assertThat(quarantined)
                .extracting(
                        QuarantinedRow::rowNumber,
                        QuarantinedRow::reason,
                        QuarantinedRow::reasonDetail)
                .containsExactly(
                        tuple(
                                2,
                                QuarantineReason.DUPLICATE_IN_FILE,
                                "legacy_student_id appears 2 times in the file"),
                        tuple(
                                4,
                                QuarantineReason.DUPLICATE_IN_FILE,
                                "legacy_student_id appears 2 times in the file"));
        assertThat(upserted)
                .extracting(LegacyStudentRecord::legacyStudentId)
                .containsExactly("SV002");
    }

    @Test
    @DisplayName("a repeated email is rejected on every occurrence, the first included")
    void duplicateEmail_everyOccurrenceRejected() {
        givenExport(
                numbered(
                        HEADER,
                        "SV001,same@uni.edu,First",
                        "SV002,same@uni.edu,Second",
                        "SV003,c@uni.edu,Unique"));

        useCase(500).run();

        assertThat(quarantined)
                .extracting(QuarantinedRow::rowNumber, QuarantinedRow::reason)
                .containsExactly(
                        tuple(2, QuarantineReason.DUPLICATE_IN_FILE),
                        tuple(3, QuarantineReason.DUPLICATE_IN_FILE));
        assertThat(quarantined.get(0).reasonDetail())
                .isEqualTo("email appears 2 times in the file");
        assertThat(upserted)
                .extracting(LegacyStudentRecord::legacyStudentId)
                .containsExactly("SV003");
    }

    @Test
    @DisplayName("a malformed row does not count towards duplicates of a good row")
    void malformedRow_notCountedForDuplicates() {
        givenExport(numbered(HEADER, "SV001,a@uni.edu,Good", "SV001,a@uni.edu"));

        useCase(500).run();

        assertThat(upserted)
                .extracting(LegacyStudentRecord::legacyStudentId)
                .containsExactly("SV001");
        assertThat(quarantined)
                .extracting(QuarantinedRow::reason)
                .containsExactly(QuarantineReason.MALFORMED_ROW);
    }

    // ---------- batching and mid-run failure (user.md §5, §7) ----------

    @Test
    @DisplayName("commits every batchSize rows and sums the batches")
    void commitsInBatches() {
        givenExport(
                numbered(
                        HEADER,
                        "SV001,a@uni.edu,A",
                        "SV002,b@uni.edu,B",
                        "SV003,c@uni.edu,C",
                        "SV004,d@uni.edu",
                        "SV005,e@uni.edu,E"));

        var result = useCase(2).run();

        InOrder order = Mockito.inOrder(runs);
        order.verify(runs).recordProgress(RUN, new SyncProgress(2, 2, 0, 0));
        order.verify(runs).recordProgress(RUN, new SyncProgress(2, 1, 0, 1));
        order.verify(runs).recordProgress(RUN, new SyncProgress(1, 1, 0, 0));
        order.verify(runs).complete(RUN);
        assertThat(result)
                .isEqualTo(new RunNightlySyncResult.Completed(RUN, EXPORT_DATE, 5, 4, 0, 1));
    }

    @Test
    @DisplayName("a failure mid-run: FAILED / PIPELINE_ERROR, earlier batches stay committed")
    void midRunFailure_pipelineError() {
        givenExport(
                numbered(HEADER, "SV001,a@uni.edu,A", "SV002,b@uni.edu,B", "SV003,c@uni.edu,C"));
        when(accounts.upsert(anyList()))
                .thenReturn(new UpsertOutcome(2, 0))
                .thenThrow(new IllegalStateException("connection lost"));

        var result = useCase(2).run();

        assertThat(result).isInstanceOf(RunNightlySyncResult.Failed.class);
        assertThat(((RunNightlySyncResult.Failed) result).failureReason())
                .isEqualTo("PIPELINE_ERROR");
        verify(runs).recordProgress(RUN, new SyncProgress(2, 2, 0, 0));
        verify(runs)
                .fail(eq(RUN), eq(SyncFailureReason.PIPELINE_ERROR), contains("connection lost"));
        verify(runs, never()).complete(any());
    }
}
