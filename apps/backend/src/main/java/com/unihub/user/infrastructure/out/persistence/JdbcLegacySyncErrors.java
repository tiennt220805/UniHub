package com.unihub.user.infrastructure.out.persistence;

import com.unihub.user.application.port.out.LegacySyncErrors;
import com.unihub.user.application.port.out.QuarantinedRow;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Inserts quarantined rows into {@code legacy_sync_errors} (user.md §6), one JDBC batch. */
@Repository
class JdbcLegacySyncErrors implements LegacySyncErrors {

    private static final String INSERT =
            "INSERT INTO legacy_sync_errors"
                    + " (sync_run_id, row_number, raw_row, reason_code, reason_detail)"
                    + " VALUES (?, ?, ?, ?, ?)";

    /** Postgres text cannot hold U+0000; the row is kept verbatim apart from that one character. */
    private static final char NUL = '\u0000';

    private static final char REPLACEMENT = '�';

    private final JdbcTemplate jdbcTemplate;

    JdbcLegacySyncErrors(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void quarantine(UUID runId, List<QuarantinedRow> rows) {
        jdbcTemplate.batchUpdate(
                INSERT,
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setObject(1, runId);
                    ps.setInt(2, row.rowNumber());
                    ps.setString(3, row.rawRow().replace(NUL, REPLACEMENT));
                    ps.setString(4, row.reason().name());
                    ps.setString(5, row.reasonDetail());
                });
    }
}
