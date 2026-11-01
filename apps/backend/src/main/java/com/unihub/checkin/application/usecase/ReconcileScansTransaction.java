package com.unihub.checkin.application.usecase;

import com.unihub.checkin.application.port.out.Attendances;
import com.unihub.checkin.application.port.out.CheckInScans;
import com.unihub.checkin.application.port.out.NewScan;
import com.unihub.checkin.domain.model.ReconciliationPolicy;
import com.unihub.checkin.domain.model.ScanKey;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short transactions of a sync (backend-coding-standards.md §19.1: the {@code *Transaction}
 * helper bean of {@link SyncScanBatch}, which holds none itself). Default isolation, {@code READ
 * COMMITTED}, is enough: the advisory lock, not the isolation level, serializes reconciliation
 * (Phase 5 checklist D52).
 */
@Service
class ReconcileScansTransaction {
    private final CheckInScans scans;
    private final Attendances attendances;

    ReconcileScansTransaction(CheckInScans scans, Attendances attendances) {
        this.scans = scans;
        this.attendances = attendances;
    }

    /** Scans judged invalid: stored, decided, outside any registration's lock. */
    @Transactional
    public void recordRejected(List<NewScan> rejected) {
        scans.insertRejected(rejected);
    }

    /**
     * Under the registration's lock: store its new scans, pick the winner among all its valid scans
     * (so the result never depends on the order batches arrived), write the attendance and the
     * outcomes together.
     */
    @Transactional
    public void reconcile(RegistrationId registration, List<NewScan> newScans) {
        scans.lockRegistration(registration);
        scans.insertUnresolved(newScans);
        var valid = scans.findValidScans(registration);
        var resolution = ReconciliationPolicy.resolve(valid);
        var checkedInAt =
                valid.stream()
                        .filter(scan -> scan.id().equals(resolution.winner()))
                        .findFirst()
                        .map(ScanKey::scannedAt)
                        .orElseThrow();
        attendances.upsert(registration, resolution.winner(), checkedInAt);
        scans.updateOutcomes(resolution.outcomes());
    }
}
