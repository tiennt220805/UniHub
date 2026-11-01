package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M-46, the arrival-order half (INV-12; Phase 5 checklist task 3.7, D52, AC52.1): the winner of a
 * ticket is the scan with the smallest {@code (scanned_at, id)}, whatever order the scans reach the
 * server in and however they are split into batches. The expected winner is asked of Postgres
 * ({@code ORDER BY scanned_at, id}), not of the Java comparator, so a disagreement between the two
 * (signed versus unsigned uuid order) shows up as a failure here. The tie is chosen to straddle
 * that boundary: ids {@code 7fff…} and {@code 8000…} on the same instant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationOrderIT extends CheckInITSupport {

    /** The shape of one scan, before it gets an id of this run's. */
    private record Plan(String name, long idHigh, String device, Instant at) {}

    private static final List<Plan> PLANS =
            List.of(
                    new Plan("late", 0x1000_0000_0000_0000L, "dev-1", T0.plusSeconds(10)),
                    new Plan("tie-high", 0x8000_0000_0000_0000L, "dev-2", T0),
                    new Plan("tie-low", 0x7fff_ffff_ffff_ffffL, "dev-1", T0),
                    new Plan("middle", 0xa000_0000_0000_0000L, "dev-2", T0.plusSeconds(5)));

    private List<Scan> scansFor(CheckInFixtures.Ticket ticket, long run) {
        return PLANS.stream()
                .map(
                        p ->
                                new Scan(
                                        new UUID(p.idHigh(), run),
                                        "VERIFIED",
                                        "\"" + ticket.code() + "\"",
                                        ticket.workshopId().value(),
                                        p.at(),
                                        p.device()))
                .toList();
    }

    /** Postgres's own answer: the smallest (scanned_at, id) of these scans. */
    private UUID winnerAccordingToPostgres(List<Scan> scans) {
        var values = new ArrayList<String>();
        var args = new ArrayList<Object>();
        for (var scan : scans) {
            values.add("(?::uuid, ?::timestamptz)");
            args.add(scan.id().toString());
            args.add(scan.scannedAt().toString());
        }
        return jdbc.queryForObject(
                "SELECT id FROM (VALUES "
                        + String.join(",", values)
                        + ") AS s(id, at)"
                        + " ORDER BY at, id LIMIT 1",
                UUID.class,
                args.toArray());
    }

    private void assertSettled(
            UUID registration, List<Scan> scans, UUID expectedWinner, String order) {
        assertThat(attendanceRows(registration)).as("one attendance, order %s", order).isEqualTo(1);
        assertThat(winningScan(registration))
                .as("winner, order %s", order)
                .isEqualTo(expectedWinner);
        for (var scan : scans) {
            assertThat(storedOutcome(scan.id()))
                    .as("outcome of %s, order %s", scan.id(), order)
                    .isEqualTo(
                            scan.id().equals(expectedWinner) ? "ACCEPTED" : "REJECTED_DUPLICATE");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT checked_in_at = (SELECT scanned_at FROM check_in_scans WHERE id = ?)"
                                        + " FROM attendances WHERE registration_id = ?",
                                Boolean.class,
                                expectedWinner,
                                registration))
                .as("checked_in_at is the winner's scanned_at, order %s", order)
                .isTrue();
    }

    @Test
    @DisplayName("AC52.1: every one of the 24 arrival orders of 4 scans ends with the same winner")
    void everyArrivalOrder_endsWithTheSameWinner() throws Exception {
        var workshop = fixtures.aWorkshop();
        var permutations = permutations(PLANS.size());
        assertThat(permutations).hasSize(24);

        var run = 0L;
        for (var order : permutations) {
            run++;
            var ticket = fixtures.aConfirmedTicket(workshop);
            var scans = scansFor(ticket, run);
            var expected = winnerAccordingToPostgres(scans);
            assertThat(expected)
                    .as("the tie is settled by the smaller id as Postgres orders it")
                    .isEqualTo(scans.get(2).id());

            var arrived = 0;
            for (var index : order) {
                outcomes(sync(staff, scans.get(index)));
                arrived++;
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM check_in_scans WHERE registration_id = ?"
                                                + " AND outcome = 'ACCEPTED'",
                                        Integer.class,
                                        ticket.registrationId()))
                        .as("exactly one ACCEPTED after %d arrivals, order %s", arrived, order)
                        .isEqualTo(1);
            }
            assertSettled(ticket.registrationId(), scans, expected, order.toString());
        }
    }

    @Test
    @DisplayName(
            "the same holds when the scans arrive in batches of different sizes, in seeded random orders")
    void shuffledBatches_endWithTheSameWinner() throws Exception {
        var workshop = fixtures.aWorkshop();
        var seed = 20261101L;
        var random = new Random(seed);
        for (var round = 1; round <= 30; round++) {
            var ticket = fixtures.aConfirmedTicket(workshop);
            var scans = new ArrayList<>(scansFor(ticket, 1000L + round));
            var expected = winnerAccordingToPostgres(scans);
            Collections.shuffle(scans, random);

            var description =
                    "seed "
                            + seed
                            + ", round "
                            + round
                            + ", order "
                            + scans.stream().map(s -> s.deviceId() + "@" + s.scannedAt()).toList();
            var from = 0;
            while (from < scans.size()) {
                var size = 1 + random.nextInt(scans.size() - from);
                outcomes(syncAs(staff, scans.subList(from, from + size)));
                from += size;
            }
            assertSettled(ticket.registrationId(), scans, expected, description);
        }
    }

    @Test
    @DisplayName(
            "a scan that arrives later but is earlier takes the win, and the old winner becomes a duplicate")
    void overtake_movesTheAttendance() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var scans = scansFor(ticket, 7);
        var late = scans.get(0);
        var early = scans.get(2);

        assertThat(outcomes(sync(staff, late))).containsEntry(late.id(), "ACCEPTED");
        var overtaking = outcomes(sync(staff, early));

        assertThat(overtaking).containsEntry(early.id(), "ACCEPTED");
        assertThat(storedOutcome(late.id())).isEqualTo("REJECTED_DUPLICATE");
        assertThat(winningScan(ticket.registrationId())).isEqualTo(early.id());
        assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("scans of different tickets never influence each other")
    void differentTickets_areIndependent() throws Exception {
        var workshop = fixtures.aWorkshop();
        var one = fixtures.aConfirmedTicket(workshop);
        var two = fixtures.aConfirmedTicket(workshop);
        var onOne = verified(one, T0.plusSeconds(100), "dev-1");
        var onTwo = verified(two, T0, "dev-2");

        var outcomes = outcomes(sync(staff, onOne, onTwo));

        assertThat(outcomes)
                .containsEntry(onOne.id(), "ACCEPTED")
                .containsEntry(onTwo.id(), "ACCEPTED");
        assertThat(winningScan(one.registrationId())).isEqualTo(onOne.id());
        assertThat(winningScan(two.registrationId())).isEqualTo(onTwo.id());
    }

    private static List<List<Integer>> permutations(int n) {
        var result = new ArrayList<List<Integer>>();
        permute(new ArrayList<>(), new boolean[n], n, result);
        return result;
    }

    private static void permute(
            List<Integer> prefix, boolean[] used, int n, List<List<Integer>> out) {
        if (prefix.size() == n) {
            out.add(List.copyOf(prefix));
            return;
        }
        for (var i = 0; i < n; i++) {
            if (!used[i]) {
                used[i] = true;
                prefix.add(i);
                permute(prefix, used, n, out);
                prefix.remove(prefix.size() - 1);
                used[i] = false;
            }
        }
    }
}
