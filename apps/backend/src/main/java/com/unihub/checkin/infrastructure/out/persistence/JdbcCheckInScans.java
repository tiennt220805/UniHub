package com.unihub.checkin.infrastructure.out.persistence;

import com.unihub.checkin.application.port.out.CheckInScans;
import com.unihub.checkin.application.port.out.NewScan;
import com.unihub.checkin.application.port.out.StoredScan;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanKey;
import com.unihub.checkin.domain.model.ScanOutcome;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JDBC persistence of {@code check_in_scans} (Phase 5 checklist D52; checkin.md §7). One statement
 * per scan so the number of rows inserted is exact ({@code ON CONFLICT (id) DO NOTHING} reports
 * {@code SUCCESS_NO_INFO} in a driver batch).
 */
@Repository
class JdbcCheckInScans implements CheckInScans {

    private static final String INSERT =
            "INSERT INTO check_in_scans (id, registration_id, scanned_by, device_id, scanned_at,"
                    + " outcome) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))";

    private static final String VALID_SCANS =
            "SELECT id, scanned_at FROM check_in_scans"
                    + " WHERE registration_id = ? AND outcome <> 'REJECTED_INVALID'";

    private static final String UPDATE_OUTCOME =
            "UPDATE check_in_scans SET outcome = ?, updated_at = now()"
                    + " WHERE id = ? AND outcome <> ? AND outcome <> 'REJECTED_INVALID'";

    private final JdbcTemplate jdbc;

    JdbcCheckInScans(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<ScanId, StoredScan> findByIds(Collection<ScanId> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var uuids = ids.stream().map(ScanId::value).toArray(UUID[]::new);
        var found = new HashMap<ScanId, StoredScan>();
        jdbc.query(
                con -> {
                    var ps =
                            con.prepareStatement(
                                    "SELECT id, registration_id, scanned_by, device_id,"
                                            + " scanned_at, outcome FROM check_in_scans"
                                            + " WHERE id = ANY (?)");
                    Array array = con.createArrayOf("uuid", uuids);
                    ps.setArray(1, array);
                    return ps;
                },
                rs -> {
                    var registration = rs.getObject("registration_id", UUID.class);
                    var scan =
                            new StoredScan(
                                    new ScanId(rs.getObject("id", UUID.class)),
                                    registration == null ? null : RegistrationId.of(registration),
                                    UserId.of(rs.getObject("scanned_by", UUID.class)),
                                    rs.getString("device_id"),
                                    rs.getObject("scanned_at", OffsetDateTime.class).toInstant(),
                                    ScanOutcome.valueOf(rs.getString("outcome")));
                    found.put(scan.id(), scan);
                });
        return found;
    }

    @Override
    public int insertRejected(List<NewScan> scans) {
        return insert(scans, ScanOutcome.REJECTED_INVALID);
    }

    @Override
    public int insertUnresolved(List<NewScan> scans) {
        for (var scan : scans) {
            if (scan.registrationId() == null) {
                throw new IllegalArgumentException(
                        "a scan to be reconciled must name its registration");
            }
        }
        // Stored as a loser, decided in the same transaction; any non-invalid value puts the scan
        // into the valid set, and a loser is the value that needs no revision when it stays one.
        return insert(scans, ScanOutcome.REJECTED_DUPLICATE);
    }

    private int insert(List<NewScan> scans, ScanOutcome outcome) {
        var inserted = 0;
        for (var scan : scans) {
            inserted +=
                    jdbc.update(
                            INSERT,
                            scan.id().value(),
                            scan.registrationId() == null ? null : scan.registrationId().value(),
                            scan.scannedBy().value(),
                            scan.deviceId(),
                            utc(scan.scannedAt()),
                            outcome.name());
        }
        return inserted;
    }

    @Override
    public void lockRegistration(RegistrationId registration) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "the registration lock lasts until the transaction ends: none is active");
        }
        jdbc.query(LOCK, rs -> {}, registration.value().toString());
    }

    @Override
    public List<ScanKey> findValidScans(RegistrationId registration) {
        return jdbc.query(
                VALID_SCANS,
                (rs, n) ->
                        new ScanKey(
                                new ScanId(rs.getObject("id", UUID.class)),
                                rs.getObject("scanned_at", OffsetDateTime.class).toInstant()),
                registration.value());
    }

    @Override
    public void updateOutcomes(Map<ScanId, ScanOutcome> outcomes) {
        outcomes.forEach(
                (id, outcome) ->
                        jdbc.update(UPDATE_OUTCOME, outcome.name(), id.value(), outcome.name()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
