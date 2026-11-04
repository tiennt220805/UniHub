package com.unihub.user.application.usecase;

import com.unihub.user.application.port.in.RunNightlySyncUseCase;
import com.unihub.user.application.port.out.ExportLine;
import com.unihub.user.application.port.out.LegacySyncRuns;
import com.unihub.user.application.port.out.StudentExport;
import com.unihub.user.application.port.out.StudentExportSource;
import com.unihub.user.application.port.out.StudentExportUnreadableException;
import com.unihub.user.application.port.out.SyncFailureReason;
import com.unihub.user.application.port.out.SyncMetrics;
import com.unihub.user.application.port.out.SyncProgress;
import com.unihub.user.application.result.RunNightlySyncResult;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The nightly legacy student sync (user.md §2–§8, INV-13). Not transactional itself: the run row is
 * written before and after the pipeline in their own commits, and each batch commits through {@link
 * NightlySyncBatchTransaction}, so every path ends in exactly one COMPLETED or FAILED row.
 */
@Service
class RunNightlySync implements RunNightlySyncUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(RunNightlySync.class);

    private final StudentExportSource exports;
    private final LegacySyncRuns runs;
    private final NightlySyncBatchTransaction batches;
    private final NightlySyncPolicy policy;
    private final SyncMetrics metrics;
    private final Clock clock;

    RunNightlySync(
            StudentExportSource exports,
            LegacySyncRuns runs,
            NightlySyncBatchTransaction batches,
            NightlySyncPolicy policy,
            SyncMetrics metrics,
            Clock clock) {
        this.exports = exports;
        this.runs = runs;
        this.batches = batches;
        this.policy = policy;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public RunNightlySyncResult run() {
        var exportDate = LocalDate.ofInstant(clock.instant(), policy.zone());
        var runId = runs.start(exportDate);

        var export = exports.find(exportDate);
        if (export.isEmpty()) {
            return fail(runId, exportDate, SyncFailureReason.FILE_NOT_FOUND, null);
        }

        ExportRowClassifier classifier;
        try {
            classifier = scan(export.get());
        } catch (StudentExportUnreadableException e) {
            return fail(runId, exportDate, SyncFailureReason.FILE_UNREADABLE, e.getMessage());
        }

        try {
            var totals = apply(runId, export.get(), classifier);
            runs.complete(runId);
            metrics.runCompleted(totals.processed(), totals.quarantined());
            LOG.info(
                    "Nightly sync run {} for {} completed: {} rows, {} created, {} updated, {}"
                            + " quarantined",
                    runId,
                    exportDate,
                    totals.processed(),
                    totals.created(),
                    totals.updated(),
                    totals.quarantined());
            return new RunNightlySyncResult.Completed(
                    runId,
                    exportDate,
                    totals.processed(),
                    totals.created(),
                    totals.updated(),
                    totals.quarantined());
        } catch (RuntimeException e) {
            LOG.error("Nightly sync run {} for {} failed mid-pipeline", runId, exportDate, e);
            return fail(runId, exportDate, SyncFailureReason.PIPELINE_ERROR, e.toString());
        }
    }

    /** Pass 1: reads the header and counts keys. Writes nothing. */
    private static ExportRowClassifier scan(StudentExport export) {
        try (var lines = export.lines()) {
            var iterator = lines.iterator();
            var header = readHeader(iterator);
            return ExportRowClassifier.scan(header, iterator);
        }
    }

    /** Pass 2: classifies each row and commits every {@code batchSize} rows. */
    private SyncProgress apply(UUID runId, StudentExport export, ExportRowClassifier classifier) {
        var totals = SyncProgress.NONE;
        try (var lines = export.lines()) {
            var iterator = lines.iterator();
            readHeader(iterator);
            List<ClassifiedRow> batch = new ArrayList<>(policy.batchSize());
            while (iterator.hasNext()) {
                batch.add(classifier.classify(iterator.next()));
                if (batch.size() == policy.batchSize()) {
                    totals = totals.plus(batches.apply(runId, batch));
                    batch = new ArrayList<>(policy.batchSize());
                }
            }
            if (!batch.isEmpty()) {
                totals = totals.plus(batches.apply(runId, batch));
            }
        }
        return totals;
    }

    private static ExportHeader readHeader(Iterator<ExportLine> lines) {
        if (!lines.hasNext()) {
            throw new StudentExportUnreadableException("file is empty: no header row", null);
        }
        return ExportHeader.parse(lines.next());
    }

    private RunNightlySyncResult fail(
            UUID runId, LocalDate exportDate, SyncFailureReason reason, String detail) {
        runs.fail(runId, reason, detail);
        LOG.error(
                "Nightly sync run {} for {} failed: {}{}",
                runId,
                exportDate,
                reason,
                detail == null ? "" : " (" + detail + ")");
        return new RunNightlySyncResult.Failed(runId, exportDate, reason.name(), detail);
    }
}
