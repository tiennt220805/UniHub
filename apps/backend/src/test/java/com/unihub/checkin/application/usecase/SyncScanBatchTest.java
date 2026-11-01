package com.unihub.checkin.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.checkin.application.command.ScanSubmission;
import com.unihub.checkin.application.command.SyncScanBatchCommand;
import com.unihub.checkin.application.port.out.ResolvedTicket;
import com.unihub.checkin.application.result.ScanResult;
import com.unihub.checkin.domain.model.DeviceVerdict;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanOutcome;
import com.unihub.checkin.domain.model.TicketStanding;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The orchestration of a sync batch with in-memory ports (Phase 5 checklist task 3.4; D50–D54). The
 * database facts (the lock really serializes, the constraints really hold) are proved by the
 * integration tests; here: what is judged valid, what is stored where, in what order, and what
 * survives a failure.
 */
class SyncScanBatchTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");
    private static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");

    private final InMemoryCheckIn db = new InMemoryCheckIn();
    private final List<AuditEntry> audited = new ArrayList<>();
    private final AuditTrail audit = audited::add;
    private final UserId staff = UserId.newId();
    private final Actor.UserActor actor = new Actor.UserActor(staff, Set.of("CHECKIN_SCAN"));
    private final WorkshopId room = WorkshopId.newId();
    private SyncScanBatch sync;

    @BeforeEach
    void setUp() {
        var transaction = new ReconcileScansTransaction(db.scans, db.attendances);
        sync =
                new SyncScanBatch(
                        db.scans,
                        db.standings,
                        transaction,
                        audit,
                        db.metrics,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------- helpers ----------

    private ResolvedTicket ticket(
            String code, WorkshopId workshop, boolean confirmed, boolean cancelled) {
        var resolved =
                new ResolvedTicket(
                        RegistrationId.newId(), new TicketStanding(confirmed, workshop, cancelled));
        db.standings.byCode.put(code, resolved);
        return resolved;
    }

    private ResolvedTicket validTicket(String code) {
        return ticket(code, room, true, false);
    }

    private ScanSubmission verified(String code, Instant at) {
        return verified(new ScanId(UUID.randomUUID()), code, at, "dev-1");
    }

    private ScanSubmission verified(ScanId id, String code, Instant at, String device) {
        return new ScanSubmission(id, DeviceVerdict.VERIFIED, code, room, at, device);
    }

    private ScanSubmission rejectedBy(Instant at) {
        return new ScanSubmission(
                new ScanId(UUID.randomUUID()),
                DeviceVerdict.REJECTED_SIGNATURE,
                null,
                room,
                at,
                "dev-1");
    }

    private List<ScanResult> run(ScanSubmission... scans) {
        return sync.sync(new SyncScanBatchCommand(actor, List.of(scans))).results();
    }

    private ScanOutcome outcomeOf(List<ScanResult> results, ScanSubmission scan) {
        return results.stream()
                .filter(r -> r.id().equals(scan.id()))
                .findFirst()
                .orElseThrow()
                .outcome();
    }

    // ---------- the first sync ----------

    @Test
    @DisplayName("a valid scan is ACCEPTED, stored for the registration and becomes the attendance")
    void validScan_isAcceptedAndRecorded() {
        var ticket = validTicket("A");
        var scan = verified("A", T0);

        var results = run(scan);

        assertThat(outcomeOf(results, scan)).isEqualTo(ScanOutcome.ACCEPTED);
        assertThat(db.scans.rows.get(scan.id()).registrationId())
                .isEqualTo(ticket.registrationId());
        assertThat(db.attendances.winners).containsEntry(ticket.registrationId(), scan.id());
        assertThat(db.attendances.checkedInAt).containsEntry(ticket.registrationId(), T0);
    }

    @Test
    @DisplayName("scanned_by is the caller of the sync, whatever else the batch says")
    void scannedBy_isTheCaller() {
        validTicket("A");
        var scan = verified("A", T0);

        run(scan);

        assertThat(db.scans.rows.get(scan.id()).scannedBy()).isEqualTo(staff);
        assertThat(db.scans.rows.get(scan.id()).deviceId()).isEqualTo("dev-1");
    }

    @Test
    @DisplayName("a scan the device rejected is stored REJECTED_INVALID with no registration")
    void deviceRejectedScan_hasNoRegistration() {
        var scan = rejectedBy(T0);

        var results = run(scan);

        assertThat(outcomeOf(results, scan)).isEqualTo(ScanOutcome.REJECTED_INVALID);
        assertThat(db.scans.rows.get(scan.id()).registrationId()).isNull();
        assertThat(db.attendances.winners).isEmpty();
        assertThat(db.standings.asked).as("no code to look up").isEmpty();
    }

    @Test
    @DisplayName("a code that resolves to no registration is REJECTED_INVALID with no registration")
    void unresolvedCode_isRejectedWithoutRegistration() {
        var scan = verified("NOBODY", T0);

        var results = run(scan);

        assertThat(outcomeOf(results, scan)).isEqualTo(ScanOutcome.REJECTED_INVALID);
        assertThat(db.scans.rows.get(scan.id()).registrationId()).isNull();
    }

    @Test
    @DisplayName(
            "a ticket that is not valid keeps its registration in the trail but makes no attendance")
    void invalidTicket_isRecordedAgainstItsRegistration() {
        var cancelledRegistration = ticket("C", room, false, false);
        var cancelledWorkshop = ticket("W", room, true, true);
        var wrongRoom = ticket("R", WorkshopId.newId(), true, false);
        var a = verified("C", T0);
        var b = verified("W", T0);
        var c = verified("R", T0);

        var results = run(a, b, c);

        assertThat(results)
                .extracting(ScanResult::outcome)
                .containsOnly(ScanOutcome.REJECTED_INVALID);
        assertThat(db.scans.rows.get(a.id()).registrationId())
                .isEqualTo(cancelledRegistration.registrationId());
        assertThat(db.scans.rows.get(b.id()).registrationId())
                .isEqualTo(cancelledWorkshop.registrationId());
        assertThat(db.scans.rows.get(c.id()).registrationId())
                .isEqualTo(wrongRoom.registrationId());
        assertThat(db.attendances.winners).isEmpty();
    }

    @Test
    @DisplayName("a REJECTED_SIGNATURE scan ignores the code it carries, however hostile")
    void rejectedSignature_ignoresItsCode() {
        validTicket("A");
        var hostile =
                new ScanSubmission(
                        new ScanId(UUID.randomUUID()),
                        DeviceVerdict.REJECTED_SIGNATURE,
                        "A",
                        room,
                        T0,
                        "dev-1");

        var results = run(hostile);

        assertThat(outcomeOf(results, hostile)).isEqualTo(ScanOutcome.REJECTED_INVALID);
        assertThat(db.scans.rows.get(hostile.id()).registrationId()).isNull();
        assertThat(db.standings.asked).isEmpty();
    }

    @Test
    @DisplayName(
            "two scans of one ticket in one batch: the earlier is ACCEPTED, the other a duplicate")
    void twoScansInOneBatch_reachTheSameResult() {
        validTicket("A");
        var late = verified("A", T0.plusSeconds(30));
        var early = verified("A", T0);

        var results = run(late, early);

        assertThat(outcomeOf(results, early)).isEqualTo(ScanOutcome.ACCEPTED);
        assertThat(outcomeOf(results, late)).isEqualTo(ScanOutcome.REJECTED_DUPLICATE);
    }

    @Test
    @DisplayName("every code of the batch is looked up in one call, each code once")
    void codesAreLookedUpOncePerBatch() {
        validTicket("A");
        validTicket("B");

        run(verified("A", T0), verified("A", T0.plusSeconds(1)), verified("B", T0));

        assertThat(db.standings.asked).hasSize(1);
        assertThat(db.standings.asked.get(0)).containsExactlyInAnyOrder("A", "B");
    }

    // ---------- resubmission ----------

    @Test
    @DisplayName("a scan sent again creates no row and returns its stored outcome")
    void resubmission_returnsTheStoredOutcome() {
        validTicket("A");
        var scan = verified("A", T0);
        run(scan);
        var rows = db.scans.rows.size();

        var results = run(scan);

        assertThat(db.scans.rows).hasSize(rows);
        assertThat(outcomeOf(results, scan)).isEqualTo(ScanOutcome.ACCEPTED);
        assertThat(db.scans.lockOrder).as("nothing new to reconcile").hasSize(1);
    }

    @Test
    @DisplayName(
            "a resubmitted scan comes back with its current outcome after an earlier scan overtook it")
    void resubmission_afterOvertake_isTheDuplicate() {
        validTicket("A");
        var first = verified("A", T0.plusSeconds(60));
        run(first);
        var earlier = verified("A", T0);
        run(earlier);

        var results = run(first);

        assertThat(outcomeOf(results, first)).isEqualTo(ScanOutcome.REJECTED_DUPLICATE);
        assertThat(db.attendances.winners.values()).containsExactly(earlier.id());
    }

    @Test
    @DisplayName(
            "the same id twice inside one batch: the second is a resubmission, one row, one result")
    void sameIdTwiceInOneBatch_isOneScan() {
        validTicket("A");
        var scan = verified("A", T0);

        var results = run(scan, scan);

        assertThat(results).hasSize(1);
        assertThat(db.scans.rows).hasSize(1);
        assertThat(outcomeOf(results, scan)).isEqualTo(ScanOutcome.ACCEPTED);
    }

    @Test
    @DisplayName("the same id with another content keeps the stored row and counts the conflict")
    void sameIdOtherContent_keepsTheStoredRow() {
        validTicket("A");
        var original = verified(new ScanId(UUID.randomUUID()), "A", T0, "dev-1");
        run(original);
        var altered = verified(original.id(), "A", T0.minusSeconds(600), "dev-OTHER");

        var results = run(altered);

        assertThat(outcomeOf(results, altered)).isEqualTo(ScanOutcome.ACCEPTED);
        assertThat(db.scans.rows.get(original.id()).deviceId()).isEqualTo("dev-1");
        assertThat(db.scans.rows.get(original.id()).scannedAt()).isEqualTo(T0);
        assertThat(db.metrics.conflictingResubmissions).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a resent id whose code now points to another registration stays with the first one")
    void resentIdWithAnotherRegistrationsCode_staysWithTheFirst() {
        var a = validTicket("A");
        var b = validTicket("B");
        var original = verified(new ScanId(UUID.randomUUID()), "A", T0, "dev-1");
        run(original);
        var redirected = verified(original.id(), "B", T0, "dev-1");

        run(redirected);

        assertThat(db.scans.rows.get(original.id()).registrationId()).isEqualTo(a.registrationId());
        assertThat(db.attendances.winners).doesNotContainKey(b.registrationId());
        assertThat(db.scans.lockOrder).doesNotContain(b.registrationId());
        assertThat(db.metrics.conflictingResubmissions).isEqualTo(1);
    }

    @Test
    @DisplayName("an identical resubmission is not counted as a conflict")
    void identicalResubmission_isNotAConflict() {
        validTicket("A");
        var scan = verified("A", T0);
        run(scan);

        run(scan);

        assertThat(db.metrics.conflictingResubmissions).isZero();
    }

    // ---------- order and failure ----------

    @Test
    @DisplayName(
            "registrations are reconciled in ascending registration id, whatever the batch order")
    void registrations_areTakenInAscendingOrder() {
        var tickets = new ArrayList<ResolvedTicket>();
        for (var i = 0; i < 6; i++) {
            tickets.add(validTicket("T" + i));
        }
        var scans = new ArrayList<ScanSubmission>();
        for (var i = 0; i < 6; i++) {
            scans.add(verified("T" + i, T0.plusSeconds(i)));
        }

        sync.sync(new SyncScanBatchCommand(actor, scans));

        var expected =
                tickets.stream()
                        .map(ResolvedTicket::registrationId)
                        .sorted(java.util.Comparator.comparing(RegistrationId::value))
                        .toList();
        assertThat(db.scans.lockOrder).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("a registration that fails does not take the others of the batch down with it")
    void oneFailure_losesNothingElse() {
        var good = validTicket("GOOD");
        var bad = validTicket("BAD");
        db.scans.failing.add(bad.registrationId());
        var goodScan = verified("GOOD", T0);
        var badScan = verified("BAD", T0);
        var rejected = rejectedBy(T0);

        assertThatThrownBy(
                        () ->
                                sync.sync(
                                        new SyncScanBatchCommand(
                                                actor, List.of(badScan, goodScan, rejected))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(db.attendances.winners).containsEntry(good.registrationId(), goodScan.id());
        assertThat(db.scans.rows).containsKeys(goodScan.id(), rejected.id());
    }

    @Test
    @DisplayName("after a failure the same batch can be sent again and completes")
    void afterAFailure_theBatchCanBeRetried() {
        var bad = validTicket("BAD");
        db.scans.failing.add(bad.registrationId());
        var badScan = verified("BAD", T0);
        assertThatThrownBy(() -> run(badScan)).isInstanceOf(IllegalStateException.class);
        db.scans.failing.clear();

        var results = run(badScan);

        assertThat(outcomeOf(results, badScan)).isEqualTo(ScanOutcome.ACCEPTED);
    }

    // ---------- clock, audit, bounds ----------

    @Test
    @DisplayName("scans stamped more than five minutes after now are counted, not refused")
    void deviceClockAhead_isCounted() {
        validTicket("A");
        var onTime = verified("A", NOW.plusSeconds(299));
        var ahead = verified("A", NOW.plusSeconds(301));

        var results = run(onTime, ahead);

        assertThat(results).hasSize(2);
        assertThat(db.metrics.clockAhead).isEqualTo(1);
    }

    @Test
    @DisplayName("one audit entry per batch, by the caller, with the counts")
    void oneAuditEntryPerBatch() {
        validTicket("A");

        run(verified("A", T0), verified("A", T0.plusSeconds(1)), rejectedBy(T0));

        assertThat(audited).hasSize(1);
        var entry = audited.get(0);
        assertThat(entry.actor()).isEqualTo(actor);
        assertThat(entry.action()).isEqualTo("CHECKIN_SCANS_SYNCED");
        assertThat(entry.metadata()).containsEntry("scans", 3);
    }

    @Test
    @DisplayName("a batch of none, or of more than 500, is refused before anything is read")
    void batchBounds() {
        assertThatThrownBy(() -> new SyncScanBatchCommand(actor, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var many = new ArrayList<ScanSubmission>();
        for (var i = 0; i < 501; i++) {
            many.add(rejectedBy(T0));
        }
        assertThatThrownBy(() -> new SyncScanBatchCommand(actor, many))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SyncScanBatchCommand(actor, many.subList(0, 500)).scans()).hasSize(500);
        assertThat(new SyncScanBatchCommand(actor, many.subList(0, 1)).scans()).hasSize(1);
    }

    @Test
    @DisplayName("a verified scan must carry a code")
    void verifiedScan_needsACode() {
        assertThatThrownBy(
                        () ->
                                new ScanSubmission(
                                        new ScanId(UUID.randomUUID()),
                                        DeviceVerdict.VERIFIED,
                                        null,
                                        room,
                                        T0,
                                        "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
