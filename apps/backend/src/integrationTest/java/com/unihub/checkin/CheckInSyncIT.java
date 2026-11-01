package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * M-45, the server half: a batch of offline scans over real HTTP and Postgres (Phase 5 checklist
 * task 3.6; D50, D53, D54; AC50.1-AC50.3, AC53.2, AC54.1-AC54.3). The device half (the batch is
 * sent when the network returns) is task 8.6.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckInSyncIT extends CheckInITSupport {

    // ---------- the first sync ----------

    @Test
    @DisplayName(
            "a valid scan is ACCEPTED: stored for the registration, by the caller, one attendance")
    void validScan_isAcceptedAndRecorded() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var scan = verified(ticket, T0, "dev-1");

        var outcomes = outcomes(sync(staff, scan));

        assertThat(outcomes).containsEntry(scan.id(), "ACCEPTED");
        var row = jdbc.queryForMap("SELECT * FROM check_in_scans WHERE id = ?", scan.id());
        assertThat(row.get("registration_id")).isEqualTo(ticket.registrationId());
        assertThat(row.get("scanned_by")).isEqualTo(staff.value());
        assertThat(row.get("device_id")).isEqualTo("dev-1");
        assertThat(((java.sql.Timestamp) row.get("scanned_at")).toInstant()).isEqualTo(T0);
        assertThat(winningScan(ticket.registrationId())).isEqualTo(scan.id());
    }

    @Test
    @DisplayName("AC53.2: scanned_by is the caller of each sync, never a scanner named in the body")
    void scannedBy_isTheCaller() throws Exception {
        var other = fixtures.aStaffMember();
        var workshop = fixtures.aWorkshop();
        var mine = verified(fixtures.aConfirmedTicket(workshop), T0, "dev-1");
        var theirs = verified(fixtures.aConfirmedTicket(workshop), T0, "dev-2");
        var forged =
                batch(List.of(theirs))
                        .replace(
                                "\"deviceId\"",
                                "\"scannedBy\":\"" + staff.value() + "\",\"deviceId\"");

        outcomes(sync(staff, mine));
        outcomes(syncBody(token(other), forged));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT scanned_by FROM check_in_scans WHERE id = ?",
                                UUID.class,
                                mine.id()))
                .isEqualTo(staff.value());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scanned_by FROM check_in_scans WHERE id = ?",
                                UUID.class,
                                theirs.id()))
                .isEqualTo(other.value());
    }

    @Test
    @DisplayName(
            "two scans of one ticket in one batch: the earlier is ACCEPTED, the other a duplicate")
    void twoScansOfOneTicket_inOneBatch() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var late = verified(ticket, T0.plusSeconds(30), "dev-1");
        var early = verified(ticket, T0, "dev-2");

        var outcomes = outcomes(sync(staff, late, early));

        assertThat(outcomes)
                .containsEntry(early.id(), "ACCEPTED")
                .containsEntry(late.id(), "REJECTED_DUPLICATE");
        assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
        assertThat(winningScan(ticket.registrationId())).isEqualTo(early.id());
    }

    // ---------- the bounds (BVA) ----------

    @Test
    @DisplayName(
            "AC54.2: a batch of 1 is 200, of 500 is 200, of 0 or 501 is 400 and stores nothing")
    void batchBounds() throws Exception {
        var venue = fixtures.aWorkshop();
        var one = rejectedBy(venue, T0, "null");
        assertThat(sync(staff, one).getStatusCode()).isEqualTo(HttpStatus.OK);

        var five_hundred = new ArrayList<Scan>();
        for (var i = 0; i < 500; i++) {
            five_hundred.add(rejectedBy(venue, T0.plusSeconds(i), "null"));
        }
        var full = syncAs(staff, five_hundred);
        assertThat(outcomes(full)).hasSize(500);
        assertThat(scansOf(staff)).isEqualTo(501);

        var tooMany = new ArrayList<>(five_hundred);
        tooMany.add(rejectedBy(venue, T0, "null"));
        var rejectedBatch = syncAs(staff, tooMany);
        assertThat(rejectedBatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(syncAs(staff, List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(scansOf(staff)).as("a 400 stores nothing").isEqualTo(501);
    }

    @Test
    @DisplayName("a malformed scan fails the whole batch and none of its other scans is stored")
    void malformedScan_failsTheBatch() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var good = verified(ticket, T0, "dev-1");
        var body = batch(List.of(good)).replace("]}", ",{\"id\":\"not-a-uuid\"}]}");

        var response = syncBody(token(staff), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(scanRows(ticket.registrationId())).isZero();
    }

    @Test
    @DisplayName("no token is 401, a student's token is 403, and neither stores anything")
    void permission() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var scan = verified(ticket, T0, "dev-1");

        assertThat(syncBody(null, batch(List.of(scan))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var student =
                com.unihub.shared.security.TestJwts.valid(UUID.randomUUID(), "REGISTRATION_CREATE");
        assertThat(syncBody(student, batch(List.of(scan))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(scanRows(ticket.registrationId())).isZero();
    }

    // ---------- resending ----------

    @Test
    @DisplayName("AC54.1: the same batch sent again creates no row and returns the same outcomes")
    void resentBatch_createsNothing() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var a = verified(ticket, T0, "dev-1");
        var b = verified(ticket, T0.plusSeconds(9), "dev-2");
        var unknown =
                new Scan(
                        UUID.randomUUID(),
                        "VERIFIED",
                        "\"NO-SUCH-TICKET\"",
                        ticket.workshopId().value(),
                        T0,
                        "dev-1");
        var first = outcomes(sync(staff, a, b, unknown));
        var rows = scansOf(staff);

        var again = outcomes(sync(staff, a, b, unknown));

        assertThat(again).isEqualTo(first);
        assertThat(scansOf(staff)).isEqualTo(rows);
        assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "AC54.3: a scan resent after an earlier one overtook it comes back REJECTED_DUPLICATE")
    void resentScan_afterAnOvertake_isTheDuplicate() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var recorded = verified(ticket, T0.plusSeconds(60), "dev-1");
        var overtaking = verified(ticket, T0, "dev-2");
        assertThat(outcomes(sync(staff, recorded))).containsEntry(recorded.id(), "ACCEPTED");

        assertThat(outcomes(sync(staff, overtaking))).containsEntry(overtaking.id(), "ACCEPTED");
        var resent = outcomes(sync(staff, recorded));

        assertThat(resent).containsEntry(recorded.id(), "REJECTED_DUPLICATE");
        assertThat(storedOutcome(recorded.id())).isEqualTo("REJECTED_DUPLICATE");
        assertThat(winningScan(ticket.registrationId())).isEqualTo(overtaking.id());
        assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same id with other content keeps the stored row and answers with its outcome")
    void sameIdOtherContent_keepsTheStoredRow() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var original = verified(ticket, T0, "dev-1");
        outcomes(sync(staff, original));
        var altered = original.at(T0.minusSeconds(3600)).onDevice("dev-OTHER");
        var neighbour = verified(fixtures.aConfirmedTicket(ticket.workshopId()), T0, "dev-1");

        var outcomes = outcomes(sync(staff, altered, neighbour));

        assertThat(outcomes)
                .containsEntry(original.id(), "ACCEPTED")
                .containsEntry(neighbour.id(), "ACCEPTED");
        var row =
                jdbc.queryForMap(
                        "SELECT device_id, scanned_at FROM check_in_scans WHERE id = ?",
                        original.id());
        assertThat(row.get("device_id")).isEqualTo("dev-1");
        assertThat(((java.sql.Timestamp) row.get("scanned_at")).toInstant()).isEqualTo(T0);
    }

    @Test
    @DisplayName("a resent id whose code now names another registration stays with the first one")
    void resentIdWithAnotherCode_staysWithTheFirstRegistration() throws Exception {
        var workshop = fixtures.aWorkshop();
        var first = fixtures.aConfirmedTicket(workshop);
        var second = fixtures.aConfirmedTicket(workshop);
        var original = verified(first, T0, "dev-1");
        outcomes(sync(staff, original));
        var redirected =
                new Scan(
                        original.id(),
                        "VERIFIED",
                        "\"" + second.code() + "\"",
                        workshop.value(),
                        T0,
                        "dev-1");

        outcomes(sync(staff, redirected));

        assertThat(scanRows(first.registrationId())).isEqualTo(1);
        assertThat(scanRows(second.registrationId())).isZero();
        assertThat(attendanceRows(second.registrationId())).isZero();
    }

    @Test
    @DisplayName("the same id twice inside one batch is one scan, one row, one result")
    void sameIdTwiceInOneBatch() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var scan = verified(ticket, T0, "dev-1");

        var response = sync(staff, scan, scan);

        assertThat(outcomes(response)).hasSize(1).containsEntry(scan.id(), "ACCEPTED");
        assertThat(scanRows(ticket.registrationId())).isEqualTo(1);
    }

    // ---------- scans that resolve to nothing (D50) ----------

    @Test
    @DisplayName(
            "AC50.1: a verified code nobody holds is stored REJECTED_INVALID, no registration, no attendance")
    void unknownTicket_isStoredRejected() throws Exception {
        var venue = fixtures.aWorkshop();
        var scan =
                new Scan(
                        UUID.randomUUID(),
                        "VERIFIED",
                        "\"NO-SUCH-TICKET\"",
                        venue.value(),
                        T0,
                        "dev-1");

        var outcomes = outcomes(sync(staff, scan));

        assertThat(outcomes).containsEntry(scan.id(), "REJECTED_INVALID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT registration_id FROM check_in_scans WHERE id = ?",
                                UUID.class,
                                scan.id()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM attendances WHERE winning_scan_id = ?",
                                Integer.class,
                                scan.id()))
                .isZero();
    }

    @Test
    @DisplayName("AC50.2: a scan the device rejected is synced and stored, with no registration")
    void deviceRejectedScan_isStored() throws Exception {
        var venue = fixtures.aWorkshop();
        var scan = rejectedBy(venue, T0, "null");

        var outcomes = outcomes(sync(staff, scan));

        assertThat(outcomes).containsEntry(scan.id(), "REJECTED_INVALID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT registration_id FROM check_in_scans WHERE id = ?",
                                UUID.class,
                                scan.id()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT scanned_by FROM check_in_scans WHERE id = ?",
                                UUID.class,
                                scan.id()))
                .isEqualTo(staff.value());
    }

    @Test
    @DisplayName("AC50.3: hostile ticketCodes of rejected scans do not stop the rest of the batch")
    void poisonBatch_doesNotBlockTheSync() throws Exception {
        var venue = fixtures.aWorkshop();
        var ticket = fixtures.aConfirmedTicket(venue);
        var good = verified(ticket, T0, "dev-1");
        var hostile = new ArrayList<Scan>();
        for (var raw :
                List.of(
                        "\"" + "X".repeat(10_000) + "\"",
                        "{\"a\":[1,2,{\"b\":null}]}",
                        "[\"A\",\"B\"]",
                        "12345678901234567890",
                        "true",
                        "\"" + ticket.code() + "\"")) {
            hostile.add(rejectedBy(venue, T0.minusSeconds(5), raw));
        }
        var all = new ArrayList<>(hostile);
        all.add(good);

        var response = syncAs(staff, all);

        var outcomes = outcomes(response);
        assertThat(outcomes).hasSize(7).containsEntry(good.id(), "ACCEPTED");
        hostile.forEach(h -> assertThat(outcomes).containsEntry(h.id(), "REJECTED_INVALID"));
        hostile.forEach(
                h ->
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT registration_id FROM check_in_scans WHERE id = ?",
                                                UUID.class,
                                                h.id()))
                                .as(
                                        "a rejected scan never reaches a registration, even with a real code in it")
                                .isNull());
        assertThat(winningScan(ticket.registrationId())).isEqualTo(good.id());
    }

    @Test
    @DisplayName("a device clock far ahead is accepted, not refused")
    void deviceClockAhead_isAccepted() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var scan = verified(ticket, Instant.parse("2099-01-01T00:00:00Z"), "dev-1");

        assertThat(outcomes(sync(staff, scan))).containsEntry(scan.id(), "ACCEPTED");
    }
}
