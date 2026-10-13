package com.unihub.user.infrastructure.out.persistence;

import com.unihub.user.application.port.out.LegacySyncRuns;
import com.unihub.user.application.port.out.SyncFailureReason;
import com.unihub.user.application.port.out.SyncProgress;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The state transitions of user.md §5, against {@code legacy_sync_runs}. */
@Repository
class JdbcLegacySyncRuns implements LegacySyncRuns {

    private static final String START =
            "INSERT INTO legacy_sync_runs (export_date, status) VALUES (?, 'RUNNING') RETURNING id";

    private static final String RECORD_PROGRESS =
            "UPDATE legacy_sync_runs SET rows_processed = rows_processed + ?,"
                    + " rows_created = rows_created + ?, rows_updated = rows_updated + ?,"
                    + " rows_quarantined = rows_quarantined + ? WHERE id = ?";

    private static final String COMPLETE =
            "UPDATE legacy_sync_runs SET status = 'COMPLETED', finished_at = now() WHERE id = ?";

    private static final String FAIL =
            "UPDATE legacy_sync_runs SET status = 'FAILED', failure_reason = ?,"
                    + " failure_detail = ?, finished_at = now() WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    JdbcLegacySyncRuns(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UUID start(LocalDate exportDate) {
        return jdbcTemplate.queryForObject(START, UUID.class, exportDate);
    }

    @Override
    public void recordProgress(UUID runId, SyncProgress batch) {
        jdbcTemplate.update(
                RECORD_PROGRESS,
                batch.processed(),
                batch.created(),
                batch.updated(),
                batch.quarantined(),
                runId);
    }

    @Override
    public void complete(UUID runId) {
        jdbcTemplate.update(COMPLETE, runId);
    }

    @Override
    public void fail(UUID runId, SyncFailureReason reason, String detail) {
        jdbcTemplate.update(FAIL, reason.name(), detail, runId);
    }
}
