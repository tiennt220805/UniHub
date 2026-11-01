package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.security.TestJwts;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * M-47 (INV-12; Phase 5 checklist task 3.9, D51, AC51.1-AC51.4, S-11): a ticket whose signature is
 * authentic is still not an attendance when the registration is not {@code CONFIRMED}, the workshop
 * was cancelled, or it is scanned in another workshop's room. No endpoint cancels a registration
 * yet (checklist Q7, standards §27 #8), so that branch sets the status by SQL, as the fixtures do;
 * the cancelled-workshop branch goes through the real HTTP endpoint. Every rejecting test sends a
 * valid control scan in the same batch, so a rejection cannot come from a broken setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckInValidityIT extends CheckInITSupport {

    private void cancelWorkshopOverHttp(UUID workshop) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(UUID.randomUUID(), "WORKSHOP_EDIT"));
        var response =
                http.exchange(
                        "/api/v1/workshops/" + workshop + "/cancel",
                        HttpMethod.POST,
                        new HttpEntity<>(headers),
                        String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void assertNoAttendance(UUID registration) {
        assertThat(attendanceRows(registration)).isZero();
    }

    // ---------- the registration is not CONFIRMED (AC51.1) ----------

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "EXPIRED", "RESERVED"})
    @DisplayName(
            "AC51.1: a ticket whose registration is not CONFIRMED is REJECTED_INVALID, with no attendance")
    void unconfirmedRegistration_isRejected(String status) throws Exception {
        var workshop = fixtures.aWorkshop();
        var ticket = fixtures.aTicket(workshop, status);
        var control = fixtures.aConfirmedTicket(workshop);
        var scan = verified(ticket, T0, "dev-1");
        var good = verified(control, T0, "dev-1");

        var outcomes = outcomes(sync(staff, scan, good));

        assertThat(outcomes)
                .containsEntry(scan.id(), "REJECTED_INVALID")
                .containsEntry(good.id(), "ACCEPTED");
        assertNoAttendance(ticket.registrationId());
        assertThat(scanRows(ticket.registrationId()))
                .as("the scan is kept against its registration")
                .isEqualTo(1);
    }

    // ---------- the workshop was cancelled (AC51.2) ----------

    @Test
    @DisplayName("AC51.2: a CONFIRMED ticket of a workshop cancelled over HTTP is REJECTED_INVALID")
    void cancelledWorkshop_isRejected() throws Exception {
        var workshop = fixtures.aWorkshop();
        var other = fixtures.aWorkshop();
        var ticket = fixtures.aConfirmedTicket(workshop);
        var control = fixtures.aConfirmedTicket(other);
        cancelWorkshopOverHttp(workshop.value());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM registrations WHERE id = ?",
                                String.class,
                                ticket.registrationId()))
                .as("cancelling a workshop does not cancel its registrations (standards §27 #8)")
                .isEqualTo("CONFIRMED");
        var scan = verified(ticket, T0, "dev-1");
        var good = verified(control, T0, "dev-1");

        var outcomes = outcomes(sync(staff, scan, good));

        assertThat(outcomes)
                .containsEntry(scan.id(), "REJECTED_INVALID")
                .containsEntry(good.id(), "ACCEPTED");
        assertNoAttendance(ticket.registrationId());
    }

    // ---------- the wrong room (AC51.3) ----------

    @Test
    @DisplayName("AC51.3: a valid ticket scanned in another workshop's room is REJECTED_INVALID")
    void wrongRoom_isRejected() throws Exception {
        var workshop = fixtures.aWorkshop();
        var neighbour = fixtures.aWorkshop();
        var ticket = fixtures.aConfirmedTicket(workshop);
        var scan = verified(ticket, T0, "dev-1");
        var wrongRoom =
                new Scan(
                        scan.id(),
                        scan.verdict(),
                        scan.ticketCode(),
                        neighbour.value(),
                        T0,
                        "dev-1");
        var control = verified(ticket, T0.plusSeconds(60), "dev-2");

        var first = outcomes(sync(staff, wrongRoom));
        assertThat(first).containsEntry(wrongRoom.id(), "REJECTED_INVALID");
        assertNoAttendance(ticket.registrationId());

        var second = outcomes(sync(staff, control));
        assertThat(second).as("the right room still works").containsEntry(control.id(), "ACCEPTED");
        assertThat(winningScan(ticket.registrationId())).isEqualTo(control.id());
    }

    @Test
    @DisplayName("a venue that is no workshop at all counts as the wrong room")
    void unknownVenue_isRejected() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var scan =
                new Scan(
                        UUID.randomUUID(),
                        "VERIFIED",
                        "\"" + ticket.code() + "\"",
                        UUID.randomUUID(),
                        T0,
                        "dev-1");

        assertThat(outcomes(sync(staff, scan))).containsEntry(scan.id(), "REJECTED_INVALID");
        assertNoAttendance(ticket.registrationId());
    }

    // ---------- judged once, at its own reconciliation (AC51.4) ----------

    @Test
    @DisplayName(
            "AC51.4: after a check-in, a cancelled workshop and an earlier scan: the earlier is invalid, the attendance stays")
    void checkIn_thenWorkshopCancelled_thenEarlierScan() throws Exception {
        var workshop = fixtures.aWorkshop();
        var ticket = fixtures.aConfirmedTicket(workshop);
        var s1 = verified(ticket, T0.plusSeconds(60), "dev-1");
        assertThat(outcomes(sync(staff, s1))).containsEntry(s1.id(), "ACCEPTED");
        cancelWorkshopOverHttp(workshop.value());
        var s0 = verified(ticket, T0, "dev-2");

        var outcomes = outcomes(sync(staff, s0));

        assertThat(outcomes).containsEntry(s0.id(), "REJECTED_INVALID");
        assertThat(storedOutcome(s1.id())).isEqualTo("ACCEPTED");
        assertThat(winningScan(ticket.registrationId())).isEqualTo(s1.id());
        assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
        assertThat(outcomes(sync(staff, s1)))
                .as("a resend of S1")
                .containsEntry(s1.id(), "ACCEPTED");
    }

    @Test
    @DisplayName("the same when the registration is cancelled after the check-in")
    void checkIn_thenRegistrationCancelled_thenEarlierScan() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var s1 = verified(ticket, T0.plusSeconds(60), "dev-1");
        outcomes(sync(staff, s1));
        jdbc.update(
                "UPDATE registrations SET status = 'CANCELLED' WHERE id = ?",
                ticket.registrationId());
        var s0 = verified(ticket, T0, "dev-2");

        var outcomes = outcomes(sync(staff, s0));

        assertThat(outcomes).containsEntry(s0.id(), "REJECTED_INVALID");
        assertThat(storedOutcome(s1.id())).isEqualTo("ACCEPTED");
        assertThat(winningScan(ticket.registrationId())).isEqualTo(s1.id());
    }

    @Test
    @DisplayName(
            "a scan judged invalid stays invalid when the registration later becomes valid; a new scan counts")
    void invalidVerdict_isFinal() throws Exception {
        var ticket = fixtures.aTicket(fixtures.aWorkshop(), "RESERVED");
        var early = verified(ticket, T0, "dev-1");
        assertThat(outcomes(sync(staff, early))).containsEntry(early.id(), "REJECTED_INVALID");
        jdbc.update(
                "UPDATE registrations SET status = 'CONFIRMED', hold_expires_at = NULL WHERE id = ?",
                ticket.registrationId());

        var resent = outcomes(sync(staff, early));
        assertThat(resent).containsEntry(early.id(), "REJECTED_INVALID");
        assertNoAttendance(ticket.registrationId());

        var later = verified(ticket, T0.plusSeconds(30), "dev-1");
        assertThat(outcomes(sync(staff, later))).containsEntry(later.id(), "ACCEPTED");
        assertThat(winningScan(ticket.registrationId())).isEqualTo(later.id());
        assertThat(storedOutcome(early.id())).isEqualTo("REJECTED_INVALID");
    }

    @Test
    @DisplayName(
            "an invalid scan never takes part in picking the winner, even when it is the earliest")
    void invalidScan_neverWins() throws Exception {
        var workshop = fixtures.aWorkshop();
        var neighbour = fixtures.aWorkshop();
        var ticket = fixtures.aConfirmedTicket(workshop);
        var earliestButWrongRoom =
                new Scan(
                        UUID.randomUUID(),
                        "VERIFIED",
                        "\"" + ticket.code() + "\"",
                        neighbour.value(),
                        T0,
                        "dev-1");
        var valid = verified(ticket, T0.plusSeconds(120), "dev-2");

        var outcomes = outcomes(sync(staff, earliestButWrongRoom, valid));

        assertThat(outcomes)
                .containsEntry(earliestButWrongRoom.id(), "REJECTED_INVALID")
                .containsEntry(valid.id(), "ACCEPTED");
        assertThat(winningScan(ticket.registrationId())).isEqualTo(valid.id());
    }
}
