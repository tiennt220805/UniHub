package com.unihub.user.application.usecase;

import com.unihub.user.application.port.out.LegacyStudentAccounts;
import com.unihub.user.application.port.out.LegacyStudentAccounts.UpsertOutcome;
import com.unihub.user.application.port.out.LegacyStudentRecord;
import com.unihub.user.application.port.out.LegacySyncErrors;
import com.unihub.user.application.port.out.LegacySyncRuns;
import com.unihub.user.application.port.out.QuarantineReason;
import com.unihub.user.application.port.out.QuarantinedRow;
import com.unihub.user.application.port.out.SyncProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pass 2's apply step for one batch (user.md §3, §7): the upserts, the quarantine rows and the
 * run's counters commit together, so a failure loses at most this batch and the counters always
 * match what was committed.
 */
@Component
class NightlySyncBatchTransaction {

    static final String EMAIL_HELD_BY_ANOTHER_ACCOUNT = "email already belongs to another account";

    private final LegacyStudentAccounts accounts;
    private final LegacySyncErrors errors;
    private final LegacySyncRuns runs;

    NightlySyncBatchTransaction(
            LegacyStudentAccounts accounts, LegacySyncErrors errors, LegacySyncRuns runs) {
        this.accounts = accounts;
        this.errors = errors;
        this.runs = runs;
    }

    @Transactional
    public SyncProgress apply(UUID runId, List<ClassifiedRow> batch) {
        var quarantined = new ArrayList<QuarantinedRow>();
        var valid = new ArrayList<ClassifiedRow.Valid>();
        for (var row : batch) {
            switch (row) {
                case ClassifiedRow.Valid v -> valid.add(v);
                case ClassifiedRow.Rejected r -> quarantined.add(r.row());
            }
        }

        var students = withoutEmailsHeldElsewhere(valid, quarantined);
        var outcome = students.isEmpty() ? new UpsertOutcome(0, 0) : accounts.upsert(students);
        if (!quarantined.isEmpty()) {
            errors.quarantine(runId, quarantined);
        }
        var progress =
                new SyncProgress(
                        batch.size(), outcome.created(), outcome.updated(), quarantined.size());
        runs.recordProgress(runId, progress);
        return progress;
    }

    /**
     * {@code users.email} is UNIQUE across every account, not only synced ones. A row whose email
     * another account already holds would fail the upsert and with it the whole batch, so it is
     * quarantined instead, like any other row the legacy data gets wrong.
     */
    private List<LegacyStudentRecord> withoutEmailsHeldElsewhere(
            List<ClassifiedRow.Valid> valid, List<QuarantinedRow> quarantined) {
        if (valid.isEmpty()) {
            return List.of();
        }
        var holders =
                accounts.holdersOfEmails(valid.stream().map(v -> v.student().email()).toList());
        var students = new ArrayList<LegacyStudentRecord>(valid.size());
        for (var v : valid) {
            var holder = holders.get(v.student().email());
            if (holder != null && !holder.equals(Optional.of(v.student().legacyStudentId()))) {
                quarantined.add(
                        new QuarantinedRow(
                                v.rowNumber(),
                                v.rawRow(),
                                QuarantineReason.INVALID_FIELD,
                                EMAIL_HELD_BY_ANOTHER_ACCOUNT));
            } else {
                students.add(v.student());
            }
        }
        return students;
    }
}
