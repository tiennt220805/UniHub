package com.unihub.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.user.application.port.out.LegacyStudentAccounts;
import com.unihub.user.application.port.out.LegacyStudentAccounts.UpsertOutcome;
import com.unihub.user.application.port.out.LegacyStudentRecord;
import com.unihub.user.application.port.out.LegacySyncErrors;
import com.unihub.user.application.port.out.LegacySyncRuns;
import com.unihub.user.application.port.out.QuarantineReason;
import com.unihub.user.application.port.out.QuarantinedRow;
import com.unihub.user.application.port.out.SyncProgress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The email-holder check: a row whose email another account holds is quarantined, not upserted. */
class NightlySyncBatchTransactionTest {

    private static final UUID RUN = UUID.randomUUID();

    private final LegacyStudentAccounts accounts = mock(LegacyStudentAccounts.class);
    private final LegacySyncErrors errors = mock(LegacySyncErrors.class);
    private final LegacySyncRuns runs = mock(LegacySyncRuns.class);
    private final NightlySyncBatchTransaction transaction =
            new NightlySyncBatchTransaction(accounts, errors, runs);

    private static ClassifiedRow.Valid valid(int row, String legacyId, String email) {
        return new ClassifiedRow.Valid(
                row,
                legacyId + "," + email + ",Name",
                new LegacyStudentRecord(legacyId, email, "Name"));
    }

    @Test
    @DisplayName("email held by another student or a provisioned account → INVALID_FIELD")
    void emailHeldElsewhere_quarantined() {
        when(accounts.holdersOfEmails(any()))
                .thenReturn(
                        Map.of(
                                "own@uni.edu", Optional.of("SV001"),
                                "taken@uni.edu", Optional.of("SV999"),
                                "staff@uni.edu", Optional.empty()));
        when(accounts.upsert(anyList())).thenReturn(new UpsertOutcome(1, 1));

        var progress =
                transaction.apply(
                        RUN,
                        List.of(
                                valid(2, "SV001", "own@uni.edu"),
                                valid(3, "SV002", "taken@uni.edu"),
                                valid(4, "SV003", "staff@uni.edu"),
                                valid(5, "SV004", "new@uni.edu")));

        verify(accounts)
                .upsert(
                        List.of(
                                new LegacyStudentRecord("SV001", "own@uni.edu", "Name"),
                                new LegacyStudentRecord("SV004", "new@uni.edu", "Name")));
        verify(errors)
                .quarantine(
                        RUN,
                        List.of(
                                new QuarantinedRow(
                                        3,
                                        "SV002,taken@uni.edu,Name",
                                        QuarantineReason.INVALID_FIELD,
                                        "email already belongs to another account"),
                                new QuarantinedRow(
                                        4,
                                        "SV003,staff@uni.edu,Name",
                                        QuarantineReason.INVALID_FIELD,
                                        "email already belongs to another account")));
        assertThat(progress).isEqualTo(new SyncProgress(4, 1, 1, 2));
        verify(runs).recordProgress(RUN, progress);
    }

    @Test
    @DisplayName("a batch of only rejected rows touches no account")
    void onlyRejected_noAccountCalls() {
        var rejected =
                new QuarantinedRow(2, "x", QuarantineReason.MALFORMED_ROW, "expected 3 columns");

        var progress = transaction.apply(RUN, List.of(new ClassifiedRow.Rejected(rejected)));

        verify(accounts, never()).holdersOfEmails(any());
        verify(accounts, never()).upsert(anyList());
        verify(errors).quarantine(RUN, List.of(rejected));
        assertThat(progress).isEqualTo(new SyncProgress(1, 0, 0, 1));
    }

    @Test
    @DisplayName("a batch with nothing rejected writes no quarantine row")
    void nothingRejected_noQuarantine() {
        when(accounts.holdersOfEmails(any())).thenReturn(Map.of());
        when(accounts.upsert(anyList())).thenReturn(new UpsertOutcome(1, 0));

        transaction.apply(RUN, List.of(valid(2, "SV001", "a@uni.edu")));

        verify(errors, never()).quarantine(any(), anyList());
    }
}
