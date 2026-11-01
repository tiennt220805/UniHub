package com.unihub.checkin.application.usecase;

import com.unihub.checkin.application.command.ScanSubmission;
import com.unihub.checkin.application.command.SyncScanBatchCommand;
import com.unihub.checkin.application.port.in.SyncScanBatchUseCase;
import com.unihub.checkin.application.port.out.CheckInMetrics;
import com.unihub.checkin.application.port.out.CheckInScans;
import com.unihub.checkin.application.port.out.NewScan;
import com.unihub.checkin.application.port.out.ResolvedTicket;
import com.unihub.checkin.application.port.out.StoredScan;
import com.unihub.checkin.application.port.out.TicketStandings;
import com.unihub.checkin.application.result.ScanResult;
import com.unihub.checkin.application.result.SyncScanBatchResult;
import com.unihub.checkin.domain.model.DeviceVerdict;
import com.unihub.checkin.domain.model.ReconciliationPolicy;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Uploads a batch of offline scans and reconciles them (checkin.md §7; Phase 5 checklist D50–D54).
 *
 * <p>No transaction here (backend-coding-standards.md §19.1): the batch is not one unit, because a
 * scan that fails must not lose the other 499. Scans judged invalid are stored in one short
 * transaction; the valid ones are grouped by registration and each group is reconciled in a
 * transaction of its own, under that registration's lock, in ascending registration id so two
 * batches cannot deadlock. If a group fails the rest are still done and the failure is thrown at
 * the end: the device sends the batch again, which is safe because a scan is identified by its own
 * id.
 */
@Service
class SyncScanBatch implements SyncScanBatchUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(SyncScanBatch.class);

    /** Beyond this, a scan's clock is counted as ahead of the server's (checkin.md §8). */
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

    private final CheckInScans scans;
    private final TicketStandings standings;
    private final ReconcileScansTransaction transaction;
    private final AuditTrail auditTrail;
    private final CheckInMetrics metrics;
    private final Clock clock;

    SyncScanBatch(
            CheckInScans scans,
            TicketStandings standings,
            ReconcileScansTransaction transaction,
            AuditTrail auditTrail,
            CheckInMetrics metrics,
            Clock clock) {
        this.scans = scans;
        this.standings = standings;
        this.transaction = transaction;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public SyncScanBatchResult sync(SyncScanBatchCommand command) {
        var now = clock.instant();
        // The same id twice in one batch is one scan: the later copy is a resubmission.
        var unique = new LinkedHashMap<ScanId, ScanSubmission>();
        command.scans().forEach(scan -> unique.putIfAbsent(scan.id(), scan));

        var stored = scans.findByIds(unique.keySet());
        var resolved = resolveCodes(unique.values());

        var rejected = new ArrayList<NewScan>();
        var valid =
                new TreeMap<RegistrationId, List<NewScan>>(
                        Comparator.comparing(RegistrationId::value));
        var ahead = 0;
        for (var scan : unique.values()) {
            var ticket = ticketOf(scan, resolved);
            var existing = stored.get(scan.id());
            if (existing != null) {
                noteConflict(scan, existing, ticket);
                continue;
            }
            if (scan.scannedAt().isAfter(now.plus(CLOCK_TOLERANCE))) {
                ahead++;
            }
            var isValid =
                    ReconciliationPolicy.isValid(
                            scan.verdict(), scan.venue(), ticket.map(ResolvedTicket::standing));
            var registration = ticket.map(ResolvedTicket::registrationId).orElse(null);
            var row =
                    new NewScan(
                            scan.id(),
                            registration,
                            command.actor().userId(),
                            scan.deviceId(),
                            scan.scannedAt());
            if (isValid) {
                valid.computeIfAbsent(registration, r -> new ArrayList<>()).add(row);
            } else {
                rejected.add(row);
            }
        }
        if (ahead > 0) {
            metrics.deviceClockAhead(ahead);
        }

        var failures = new ArrayList<RuntimeException>();
        if (!rejected.isEmpty()) {
            attempt(failures, () -> transaction.recordRejected(rejected));
        }
        valid.forEach(
                (registration, rows) ->
                        attempt(failures, () -> transaction.reconcile(registration, rows)));
        if (!failures.isEmpty()) {
            var first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }

        auditTrail.record(
                AuditEntry.of(
                                command.actor(),
                                AuditActions.CHECKIN_SCANS_SYNCED,
                                AuditTargets.CHECK_IN_BATCH,
                                UUID.randomUUID(),
                                now)
                        .withMetadata(
                                Map.of(
                                        "scans",
                                        command.scans().size(),
                                        "new",
                                        rejected.size() + countRows(valid))));
        return new SyncScanBatchResult(currentOutcomes(unique));
    }

    private Map<String, ResolvedTicket> resolveCodes(Iterable<ScanSubmission> submissions) {
        var codes = new java.util.LinkedHashSet<String>();
        for (var scan : submissions) {
            if (scan.verdict() == DeviceVerdict.VERIFIED) {
                codes.add(scan.ticketCode());
            }
        }
        return codes.isEmpty() ? Map.of() : standings.findByCodes(List.copyOf(codes));
    }

    /** Only a scan the device verified has a code worth resolving. */
    private static Optional<ResolvedTicket> ticketOf(
            ScanSubmission scan, Map<String, ResolvedTicket> resolved) {
        return scan.verdict() == DeviceVerdict.VERIFIED
                ? Optional.ofNullable(resolved.get(scan.ticketCode()))
                : Optional.empty();
    }

    /**
     * A scan id already stored keeps its row whatever the new copy says (checkin.md §7). A copy
     * that differs is logged and counted, never a failure: one odd scan must not block the other
     * 499.
     */
    private void noteConflict(
            ScanSubmission scan, StoredScan existing, Optional<ResolvedTicket> ticket) {
        var registration = ticket.map(ResolvedTicket::registrationId).orElse(null);
        var same =
                existing.deviceId().equals(scan.deviceId())
                        && existing.scannedAt()
                                .truncatedTo(ChronoUnit.MICROS)
                                .equals(scan.scannedAt().truncatedTo(ChronoUnit.MICROS))
                        && Objects.equals(existing.registrationId(), registration);
        if (!same) {
            metrics.conflictingResubmission();
            LOG.warn(
                    "scan {} sent again with other content than the stored row; the stored row is kept",
                    scan.id().value());
        }
    }

    private List<ScanResult> currentOutcomes(Map<ScanId, ScanSubmission> unique) {
        var current = scans.findByIds(unique.keySet());
        var results = new ArrayList<ScanResult>();
        for (var id : unique.keySet()) {
            var row = current.get(id);
            if (row == null) {
                throw new IllegalStateException("scan " + id.value() + " was not stored");
            }
            results.add(new ScanResult(id, row.outcome()));
        }
        return results;
    }

    private static int countRows(Map<RegistrationId, List<NewScan>> valid) {
        return valid.values().stream().mapToInt(List::size).sum();
    }

    private static void attempt(List<RuntimeException> failures, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOG.error("reconciliation of part of a sync batch failed", e);
            failures.add(e);
        }
    }
}
