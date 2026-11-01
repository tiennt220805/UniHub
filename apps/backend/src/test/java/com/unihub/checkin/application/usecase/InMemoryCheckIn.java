package com.unihub.checkin.application.usecase;

import com.unihub.checkin.application.port.out.Attendances;
import com.unihub.checkin.application.port.out.CheckInMetrics;
import com.unihub.checkin.application.port.out.CheckInScans;
import com.unihub.checkin.application.port.out.NewScan;
import com.unihub.checkin.application.port.out.ResolvedTicket;
import com.unihub.checkin.application.port.out.StoredScan;
import com.unihub.checkin.application.port.out.TicketStandings;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanKey;
import com.unihub.checkin.domain.model.ScanOutcome;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The ports of Check-in as plain maps, for use-case tests that need to see order and failure
 * without a database. It keeps the contract the real adapter keeps (an id already stored is left
 * alone; the valid set is every stored scan not {@code REJECTED_INVALID}); the database itself is
 * proved by the integration tests. A plain class, never a bean: test classes under {@code
 * com.unihub} are component-scanned into every Spring test context.
 */
final class InMemoryCheckIn {

    final Scans scans = new Scans();
    final Attendance attendances = new Attendance();
    final Standings standings = new Standings();
    final Metrics metrics = new Metrics();

    static final class Scans implements CheckInScans {
        final Map<ScanId, StoredScan> rows = new LinkedHashMap<>();
        final List<RegistrationId> lockOrder = new ArrayList<>();
        final Set<RegistrationId> failing = new java.util.HashSet<>();
        int transactionsOpened;

        @Override
        public Map<ScanId, StoredScan> findByIds(Collection<ScanId> ids) {
            var found = new HashMap<ScanId, StoredScan>();
            ids.forEach(
                    id -> {
                        if (rows.containsKey(id)) {
                            found.put(id, rows.get(id));
                        }
                    });
            return found;
        }

        @Override
        public int insertRejected(List<NewScan> batch) {
            return insert(batch, ScanOutcome.REJECTED_INVALID);
        }

        @Override
        public int insertUnresolved(List<NewScan> batch) {
            batch.forEach(
                    s -> {
                        if (s.registrationId() == null) {
                            throw new IllegalArgumentException("must name a registration");
                        }
                    });
            return insert(batch, ScanOutcome.REJECTED_DUPLICATE);
        }

        private int insert(List<NewScan> batch, ScanOutcome outcome) {
            var inserted = 0;
            for (var s : batch) {
                if (rows.putIfAbsent(
                                s.id(),
                                new StoredScan(
                                        s.id(),
                                        s.registrationId(),
                                        s.scannedBy(),
                                        s.deviceId(),
                                        s.scannedAt(),
                                        outcome))
                        == null) {
                    inserted++;
                }
            }
            return inserted;
        }

        @Override
        public void lockRegistration(RegistrationId registration) {
            lockOrder.add(registration);
            if (failing.contains(registration)) {
                throw new IllegalStateException("simulated failure for " + registration);
            }
        }

        @Override
        public List<ScanKey> findValidScans(RegistrationId registration) {
            return rows.values().stream()
                    .filter(s -> registration.equals(s.registrationId()))
                    .filter(s -> s.outcome() != ScanOutcome.REJECTED_INVALID)
                    .map(s -> new ScanKey(s.id(), s.scannedAt()))
                    .collect(Collectors.toList());
        }

        @Override
        public void updateOutcomes(Map<ScanId, ScanOutcome> outcomes) {
            outcomes.forEach(
                    (id, outcome) -> {
                        var row = rows.get(id);
                        if (row.outcome() != ScanOutcome.REJECTED_INVALID) {
                            rows.put(
                                    id,
                                    new StoredScan(
                                            id,
                                            row.registrationId(),
                                            row.scannedBy(),
                                            row.deviceId(),
                                            row.scannedAt(),
                                            outcome));
                        }
                    });
        }

        ScanOutcome outcome(ScanId id) {
            return rows.get(id).outcome();
        }
    }

    static final class Attendance implements Attendances {
        final Map<RegistrationId, ScanId> winners = new HashMap<>();
        final Map<RegistrationId, Instant> checkedInAt = new HashMap<>();

        @Override
        public void upsert(RegistrationId registration, ScanId winner, Instant at) {
            winners.put(registration, winner);
            checkedInAt.put(registration, at);
        }
    }

    static final class Standings implements TicketStandings {
        final Map<String, ResolvedTicket> byCode = new HashMap<>();
        final List<Collection<String>> asked = new ArrayList<>();

        @Override
        public Map<String, ResolvedTicket> findByCodes(Collection<String> ticketCodes) {
            asked.add(List.copyOf(ticketCodes));
            var found = new HashMap<String, ResolvedTicket>();
            ticketCodes.forEach(
                    code -> {
                        if (byCode.containsKey(code)) {
                            found.put(code, byCode.get(code));
                        }
                    });
            return found;
        }
    }

    static final class Metrics implements CheckInMetrics {
        int clockAhead;
        int conflictingResubmissions;

        @Override
        public void deviceClockAhead(int scans) {
            clockAhead += scans;
        }

        @Override
        public void conflictingResubmission() {
            conflictingResubmissions++;
        }
    }
}
