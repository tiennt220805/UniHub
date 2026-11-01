package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * M-49, the server half: the report of one workshop and the list of its check-ins, over real HTTP
 * and Postgres, with the seeded roles and real logins (Phase 5 checklist task 5.2; D46, D62;
 * AC46.1-AC46.3). Every figure is compared with a count the test makes itself in SQL. The Replica
 * route is {@code WorkshopReportReadsReplicaIT}; the Web page is task 5.4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopReportIT extends CheckInITSupport {

    // ---------- helpers ----------

    private ResponseEntity<String> get(String bearer, String path) {
        var headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String reportPath(Object workshop) {
        return "/api/v1/workshops/" + workshop + "/report";
    }

    private static String listPath(Object workshop, String query) {
        return "/api/v1/workshops/" + workshop + "/attendances" + query;
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return JSON.readTree(response.getBody());
    }

    private JsonNode reportOf(WorkshopId workshop) throws Exception {
        return body(get(token(staffWithReportView()), reportPath(workshop.value())));
    }

    private UUID staffWithReportView() {
        return staff.value();
    }

    private String token(UUID user) {
        return TestJwts.valid(user, "REPORT_VIEW");
    }

    /** Checks a ticket in through the real sync endpoint. */
    private void checkIn(CheckInFixtures.Ticket ticket, Instant at, String device) {
        var scan = verified(ticket, at, device);
        assertThat(sync(staff, scan).getStatusCode().value()).isEqualTo(200);
        assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int registrationsIn(WorkshopId workshop, String status) {
        return count(
                "SELECT count(*) FROM registrations WHERE workshop_id = ? AND status = ?",
                workshop.value(),
                status);
    }

    private int attendancesOf(WorkshopId workshop) {
        return count(
                "SELECT count(*) FROM attendances a JOIN registrations r ON r.id = a.registration_id"
                        + " WHERE r.workshop_id = ?",
                workshop.value());
    }

    private void takeSeats(WorkshopId workshop, int seats) {
        jdbc.update("UPDATE workshops SET seats_taken = ? WHERE id = ?", seats, workshop.value());
    }

    private void cancelRegistration(CheckInFixtures.Ticket ticket) {
        jdbc.update(
                "UPDATE registrations SET status = 'CANCELLED' WHERE id = ?",
                ticket.registrationId());
    }

    private static List<String> registrationIds(JsonNode list) {
        var ids = new ArrayList<String>();
        list.get("items").forEach(i -> ids.add(i.get("registrationId").asText()));
        return ids;
    }

    // ---------- who may read (AC46.1) ----------

    @Test
    @DisplayName("AC46.1: Organizer and Admin, logged in for real, read the report and the list")
    void organizerAndAdmin_canRead() throws Exception {
        var workshop = fixtures.aWorkshop();

        for (var role : List.of("Organizer", "Admin")) {
            var bearer = loginAs(role);

            assertThat(get(bearer, reportPath(workshop.value())).getStatusCode().value())
                    .as(role + " report")
                    .isEqualTo(200);
            assertThat(get(bearer, listPath(workshop.value(), "")).getStatusCode().value())
                    .as(role + " list")
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("the decision follows the permission: a token with only REPORT_VIEW reads both")
    void permissionAlone_isEnough() {
        var workshop = fixtures.aWorkshop();
        var bearer = TestJwts.valid(UUID.randomUUID(), "REPORT_VIEW");

        assertThat(get(bearer, reportPath(workshop.value())).getStatusCode().value())
                .isEqualTo(200);
        assertThat(get(bearer, listPath(workshop.value(), "")).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("AC46.1: Student and Check-in Staff are 403; no token and an expired one are 401")
    void everyoneElse_isRefused() throws Exception {
        var workshop = fixtures.aWorkshop();
        var paths = List.of(reportPath(workshop.value()), listPath(workshop.value(), ""));
        var expired = TestJwts.expired(UUID.randomUUID(), Duration.ofMinutes(5), "REPORT_VIEW");

        for (var path : paths) {
            for (var role : List.of("Student", "Check-in Staff")) {
                var response = get(loginAs(role), path);

                assertThat(response.getStatusCode().value()).as(role + " " + path).isEqualTo(403);
                assertThat(response.getHeaders().getContentType().toString())
                        .startsWith("application/problem+json");
            }
            for (var bearer : new String[] {null, expired, "not-a-jwt"}) {
                var response = get(bearer, path);

                assertThat(response.getStatusCode().value()).as(path).isEqualTo(401);
                assertThat(response.getHeaders().getContentType().toString())
                        .startsWith("application/problem+json");
            }
        }
    }

    // ---------- the figures (AC46.2) ----------

    @Test
    @DisplayName("AC46.2: 3 CONFIRMED, 1 RESERVED, 2 checked in: 3, 1, 2 and a rate of 2/3")
    void figures_matchDirectCounts() throws Exception {
        var workshop = fixtures.aWorkshop(40);
        var one = fixtures.aConfirmedTicket(workshop);
        var two = fixtures.aConfirmedTicket(workshop);
        fixtures.aConfirmedTicket(workshop);
        fixtures.aTicket(workshop, "RESERVED");
        takeSeats(workshop, 4);
        checkIn(one, T0, "dev-1");
        checkIn(two, T0.plusSeconds(5), "dev-2");

        var report = reportOf(workshop);

        assertThat(report.get("workshopId").asText()).isEqualTo(workshop.value().toString());
        assertThat(report.get("capacity").asInt()).isEqualTo(40);
        assertThat(report.get("seatsTaken").asInt()).isEqualTo(4);
        assertThat(report.get("confirmedCount").asInt()).isEqualTo(3);
        assertThat(report.get("reservedCount").asInt()).isEqualTo(1);
        assertThat(report.get("checkedInCount").asInt()).isEqualTo(2);
        assertThat(report.get("checkInRate").asDouble()).isCloseTo(2.0 / 3.0, within(1e-9));
        // each figure is the database's own count, made here independently
        assertThat(report.get("confirmedCount").asInt())
                .isEqualTo(registrationsIn(workshop, "CONFIRMED"));
        assertThat(report.get("reservedCount").asInt())
                .isEqualTo(registrationsIn(workshop, "RESERVED"));
        assertThat(report.get("checkedInCount").asInt()).isEqualTo(attendancesOf(workshop));
        assertThat(report.get("seatsTaken").asInt())
                .isEqualTo(
                        count("SELECT seats_taken FROM workshops WHERE id = ?", workshop.value()));
    }

    @Test
    @DisplayName(
            "EXPIRED and CANCELLED count in neither total, and another workshop never leaks in")
    void expiredCancelledAndOtherWorkshops_areLeftOut() throws Exception {
        var workshop = fixtures.aWorkshop(40);
        var other = fixtures.aWorkshop(15);
        var confirmed = fixtures.aConfirmedTicket(workshop);
        fixtures.aTicket(workshop, "EXPIRED");
        fixtures.aTicket(workshop, "CANCELLED");
        fixtures.aTicket(workshop, "RESERVED");
        takeSeats(workshop, 2);
        checkIn(confirmed, T0, "dev-1");
        // the other workshop has registrations and a check-in of its own
        checkIn(fixtures.aConfirmedTicket(other), T0, "dev-1");
        fixtures.aConfirmedTicket(other);
        fixtures.aTicket(other, "RESERVED");
        takeSeats(other, 3);

        var report = reportOf(workshop);
        var otherReport = reportOf(other);

        assertThat(report.get("confirmedCount").asInt()).isEqualTo(1);
        assertThat(report.get("reservedCount").asInt()).isEqualTo(1);
        assertThat(report.get("checkedInCount").asInt()).isEqualTo(1);
        assertThat(report.get("capacity").asInt()).isEqualTo(40);
        assertThat(report.get("seatsTaken").asInt()).isEqualTo(2);
        assertThat(otherReport.get("confirmedCount").asInt()).isEqualTo(2);
        assertThat(otherReport.get("reservedCount").asInt()).isEqualTo(1);
        assertThat(otherReport.get("checkedInCount").asInt()).isEqualTo(1);
        assertThat(otherReport.get("capacity").asInt()).isEqualTo(15);
        assertThat(otherReport.get("seatsTaken").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("no CONFIRMED registration: the rate is null (present, not 0), nobody is counted")
    void noConfirmed_rateIsNull() throws Exception {
        var workshop = fixtures.aWorkshop();
        fixtures.aTicket(workshop, "RESERVED");

        var response = get(token(staff.value()), reportPath(workshop.value()));
        var report = body(response);

        assertThat(report.get("confirmedCount").asInt()).isZero();
        assertThat(report.get("checkedInCount").asInt()).isZero();
        assertThat(report.get("checkInRate").isNull()).isTrue();
        assertThat(response.getBody()).contains("\"checkInRate\":null");
    }

    @Test
    @DisplayName("a workshop with no registrations is 200 with zeros and a null rate, not 404")
    void noRegistrations_isZeros() throws Exception {
        var workshop = fixtures.aWorkshop(25);

        var report = reportOf(workshop);
        var list = body(get(token(staff.value()), listPath(workshop.value(), "")));

        assertThat(report.get("capacity").asInt()).isEqualTo(25);
        assertThat(report.get("seatsTaken").asInt()).isZero();
        assertThat(report.get("confirmedCount").asInt()).isZero();
        assertThat(report.get("reservedCount").asInt()).isZero();
        assertThat(report.get("checkedInCount").asInt()).isZero();
        assertThat(report.get("checkInRate").isNull()).isTrue();
        assertThat(list.get("items")).isEmpty();
        assertThat(list.get("page").get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("AC51.4: a check-in that outlives its cancelled registration counts, rate above 1")
    void attendanceOutlivesCancellation_rateIsNotClamped() throws Exception {
        var workshop = fixtures.aWorkshop();
        var staying = fixtures.aConfirmedTicket(workshop);
        var cancelledLater = fixtures.aConfirmedTicket(workshop);
        checkIn(staying, T0, "dev-1");
        checkIn(cancelledLater, T0.plusSeconds(5), "dev-1");
        cancelRegistration(cancelledLater);

        var report = reportOf(workshop);
        var list = body(get(token(staff.value()), listPath(workshop.value(), "")));

        assertThat(report.get("confirmedCount").asInt()).isEqualTo(1);
        assertThat(report.get("checkedInCount").asInt())
                .as("the cancelled registration's attendance is still a check-in")
                .isEqualTo(2);
        assertThat(report.get("checkInRate").asDouble()).isEqualTo(2.0);
        assertThat(list.get("page").get("totalElements").asLong())
                .as("the list and the report count by one definition")
                .isEqualTo(report.get("checkedInCount").asLong());
        assertThat(registrationIds(list)).contains(cancelledLater.registrationId().toString());
    }

    @Test
    @DisplayName("a cancelled workshop still has a report")
    void cancelledWorkshop_stillHasAReport() throws Exception {
        var workshop = fixtures.aWorkshop(30);
        checkIn(fixtures.aConfirmedTicket(workshop), T0, "dev-1");
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(UUID.randomUUID(), "WORKSHOP_EDIT"));
        var cancelled =
                http.exchange(
                        "/api/v1/workshops/" + workshop.value() + "/cancel",
                        HttpMethod.POST,
                        new HttpEntity<>(headers),
                        String.class);
        assertThat(cancelled.getStatusCode().value()).as(cancelled.getBody()).isEqualTo(200);

        var report = reportOf(workshop);

        assertThat(report.get("capacity").asInt()).isEqualTo(30);
        assertThat(report.get("confirmedCount").asInt()).isEqualTo(1);
        assertThat(report.get("checkedInCount").asInt()).isEqualTo(1);
    }

    // ---------- not found, malformed (AC46.3) ----------

    @Test
    @DisplayName("AC46.3: an unknown workshop is 404 and a malformed id is 400, for both endpoints")
    void unknownAndMalformed() {
        var bearer = token(staff.value());
        var unknown = UUID.randomUUID();

        for (var path : List.of(reportPath(unknown), listPath(unknown, ""))) {
            var response = get(bearer, path);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(404);
            assertThat(response.getHeaders().getContentType().toString())
                    .startsWith("application/problem+json");
        }
        for (var path :
                List.of(reportPath("not-a-uuid"), listPath("not-a-uuid", ""), reportPath("123"))) {
            assertThat(get(bearer, path).getStatusCode().value()).as(path).isEqualTo(400);
        }
    }

    // ---------- the list ----------

    @Test
    @DisplayName(
            "one row per attendance, checkedInAt is the winning scan's time, this workshop only")
    void list_rowsAreTheAttendancesOfThisWorkshop() throws Exception {
        var workshop = fixtures.aWorkshop();
        var other = fixtures.aWorkshop();
        var first = fixtures.aConfirmedTicket(workshop);
        var second = fixtures.aConfirmedTicket(workshop);
        fixtures.aConfirmedTicket(workshop); // never scanned: no row
        checkIn(first, T0, "dev-1");
        checkIn(second, T0.plusSeconds(60), "dev-2");
        checkIn(fixtures.aConfirmedTicket(other), T0.plusSeconds(30), "dev-1");

        var list = body(get(token(staff.value()), listPath(workshop.value(), "")));

        assertThat(registrationIds(list))
                .containsExactly(
                        first.registrationId().toString(), second.registrationId().toString());
        assertThat(Instant.parse(list.get("items").get(0).get("checkedInAt").asText()))
                .isEqualTo(T0);
        assertThat(Instant.parse(list.get("items").get(1).get("checkedInAt").asText()))
                .isEqualTo(T0.plusSeconds(60));
        assertThat(list.get("page").get("totalElements").asLong()).isEqualTo(2);
        assertThat(list.get("page").get("page").asInt()).isZero();
        assertThat(list.get("page").get("size").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("paging through every page shows each row once, in (checkedInAt, id) order")
    void list_pagingIsStable() throws Exception {
        var workshop = fixtures.aWorkshop();
        var tickets = new ArrayList<CheckInFixtures.Ticket>();
        for (var i = 0; i < 5; i++) {
            tickets.add(fixtures.aConfirmedTicket(workshop));
        }
        // two of them check in at the very same instant, so the id has to break the tie; the one
        // with the larger id goes first, so a missing tie-break would show the wrong order
        var tiedPair = new ArrayList<>(tickets.subList(0, 2));
        tiedPair.sort(
                Comparator.comparing((CheckInFixtures.Ticket t) -> t.registrationId().toString())
                        .reversed());
        checkIn(tiedPair.get(0), T0, "dev-1");
        checkIn(tiedPair.get(1), T0, "dev-2");
        for (var i = 2; i < 5; i++) {
            checkIn(tickets.get(i), T0.plusSeconds(i), "dev-1");
        }

        var seen = new ArrayList<String>();
        var pages = 0;
        for (var page = 0; page < 3; page++) {
            var list =
                    body(
                            get(
                                    token(staff.value()),
                                    listPath(workshop.value(), "?page=" + page + "&size=2")));
            seen.addAll(registrationIds(list));
            pages = list.get("page").get("totalPages").asInt();
            assertThat(list.get("page").get("totalElements").asLong()).isEqualTo(5);
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).as("no row twice, none missed").hasSize(5).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen))
                .isEqualTo(
                        new HashSet<>(
                                tickets.stream().map(t -> t.registrationId().toString()).toList()));
        var tied =
                List.of(
                                tickets.get(0).registrationId().toString(),
                                tickets.get(1).registrationId().toString())
                        .stream()
                        .sorted(Comparator.naturalOrder())
                        .toList();
        assertThat(seen.subList(0, 2)).as("the id breaks the tie").containsExactlyElementsOf(tied);
        assertThat(seen.subList(2, 5))
                .containsExactly(
                        tickets.get(2).registrationId().toString(),
                        tickets.get(3).registrationId().toString(),
                        tickets.get(4).registrationId().toString());
    }

    @Test
    @DisplayName(
            "paging bounds: page -1, size 0 and size 101 are 400; size 100 is 200; past the end is empty")
    void list_pagingBounds() throws Exception {
        var workshop = fixtures.aWorkshop();
        checkIn(fixtures.aConfirmedTicket(workshop), T0, "dev-1");
        var bearer = token(staff.value());

        for (var query : List.of("?page=-1", "?size=0", "?size=101", "?size=-3")) {
            assertThat(get(bearer, listPath(workshop.value(), query)).getStatusCode().value())
                    .as(query)
                    .isEqualTo(400);
        }
        assertThat(get(bearer, listPath(workshop.value(), "?size=100")).getStatusCode().value())
                .isEqualTo(200);
        assertThat(get(bearer, listPath(workshop.value(), "?size=1")).getStatusCode().value())
                .isEqualTo(200);
        var past = body(get(bearer, listPath(workshop.value(), "?page=9")));
        assertThat(past.get("items")).isEmpty();
        assertThat(past.get("page").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("after an overtake the list shows the new winner's time and still counts one")
    void list_followsReconciliation() throws Exception {
        var workshop = fixtures.aWorkshop();
        var ticket = fixtures.aConfirmedTicket(workshop);
        checkIn(ticket, T0.plusSeconds(120), "dev-1");
        var before = body(get(token(staff.value()), listPath(workshop.value(), "")));
        assertThat(Instant.parse(before.get("items").get(0).get("checkedInAt").asText()))
                .isEqualTo(T0.plusSeconds(120));

        // an earlier scan of the same ticket, from another device, arrives later and wins
        assertThat(sync(staff, verified(ticket, T0, "dev-2")).getStatusCode().value())
                .isEqualTo(200);

        var after = body(get(token(staff.value()), listPath(workshop.value(), "")));
        assertThat(after.get("page").get("totalElements").asLong()).isEqualTo(1);
        assertThat(Instant.parse(after.get("items").get(0).get("checkedInAt").asText()))
                .isEqualTo(T0);
        assertThat(reportOf(workshop).get("checkedInCount").asInt()).isEqualTo(1);
    }
}
