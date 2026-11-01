package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * M-52 (SC-06; Phase 5 checklist task 3.10): after offline-sync reconciliation there is exactly one
 * valid check-in per ticket and not one scan lost. Sixty scans from four devices (ten tickets with
 * five scans each, five codes nobody holds, five scans the device rejected) reach the server in
 * seeded random partial batches that overlap and repeat, until every scan has been sent at least
 * once; then the table holds exactly sixty rows, one attendance per ticket, and each winner is the
 * smallest {@code (scanned_at, id)} as Postgres orders them. A second test makes one registration
 * of a batch fail in the database: the others of that batch are kept, and sending the batch again
 * loses nothing. The device half of "no data loss" (the queue survives a restart) is M-44.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckInNoDataLossIT extends CheckInITSupport {
    private static final int TICKETS = 10;
    private static final int PER_TICKET = 5;
    private static final int DEVICES = 4;

    @AfterEach
    void dropTheFailingTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_failing_scan_insert ON check_in_scans");
        jdbc.execute("DROP FUNCTION IF EXISTS test_failing_scan_insert()");
    }

    @Test
    @DisplayName(
            "60 scans from 4 devices sent in overlapping, repeated, shuffled partial batches: 60 rows, one attendance per ticket")
    void noScanIsLost_andEachTicketHasOneWinner() throws Exception {
        var venue = fixtures.aWorkshop();
        var staffByDevice = new ArrayList<com.unihub.shared.domain.id.UserId>();
        for (var d = 0; d < DEVICES; d++) {
            staffByDevice.add(fixtures.aStaffMember());
        }
        var scansByDevice = new HashMap<Integer, List<Scan>>();
        var tickets = new ArrayList<CheckInFixtures.Ticket>();
        var scansOfTicket = new HashMap<UUID, List<Scan>>();
        var random = new Random(52_001L);
        var all = new ArrayList<Scan>();
        for (var t = 0; t < TICKETS; t++) {
            var ticket = fixtures.aConfirmedTicket(venue);
            tickets.add(ticket);
            for (var i = 0; i < PER_TICKET; i++) {
                var device = random.nextInt(DEVICES);
                // a few scans share an instant, so ties are part of the data
                var at = T0.plusSeconds(random.nextInt(6) * 10L);
                var scan = verified(ticket, at, "device-" + device);
                scansByDevice.computeIfAbsent(device, d -> new ArrayList<>()).add(scan);
                scansOfTicket
                        .computeIfAbsent(ticket.registrationId(), r -> new ArrayList<>())
                        .add(scan);
                all.add(scan);
            }
        }
        for (var i = 0; i < 5; i++) {
            var unknown =
                    new Scan(
                            UUID.randomUUID(),
                            "VERIFIED",
                            "\"NOBODY-" + i + "\"",
                            venue.value(),
                            T0,
                            "device-" + (i % DEVICES));
            scansByDevice.computeIfAbsent(i % DEVICES, d -> new ArrayList<>()).add(unknown);
            all.add(unknown);
            var rejected =
                    rejectedBy(
                            venue,
                            T0.plusSeconds(i),
                            i % 2 == 0 ? "null" : "\"" + "Z".repeat(500) + "\"");
            scansByDevice.computeIfAbsent((i + 1) % DEVICES, d -> new ArrayList<>()).add(rejected);
            all.add(rejected);
        }
        assertThat(all).hasSize(TICKETS * PER_TICKET + 10);

        // Each device sends its own scans (D53) in random overlapping chunks, repeating some, until
        // all went at least once.
        var sent = new HashSet<UUID>();
        var rounds = 0;
        while (sent.size() < all.size()) {
            rounds++;
            for (var device = 0; device < DEVICES; device++) {
                var mine = new ArrayList<>(scansByDevice.getOrDefault(device, List.of()));
                Collections.shuffle(mine, random);
                var take = Math.min(mine.size(), 1 + random.nextInt(8));
                var chunk = new ArrayList<>(mine.subList(0, take));
                // resend a few already-sent scans, in the same batch
                for (var again : mine) {
                    if (sent.contains(again.id())
                            && random.nextInt(3) == 0
                            && !chunk.contains(again)) {
                        chunk.add(again);
                    }
                }
                if (chunk.isEmpty()) {
                    continue;
                }
                var response = syncAs(staffByDevice.get(device), chunk);
                assertThat(response.getStatusCode())
                        .as(response.getBody())
                        .isEqualTo(HttpStatus.OK);
                chunk.forEach(s -> sent.add(s.id()));
            }
            assertThat(rounds).as("the loop must finish").isLessThan(500);
        }

        // Everything once more, in one go per device: pure resends change nothing.
        var rowsBefore = totalRows(all);
        for (var device = 0; device < DEVICES; device++) {
            var mine = scansByDevice.getOrDefault(device, List.of());
            if (!mine.isEmpty()) {
                assertThat(syncAs(staffByDevice.get(device), mine).getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            }
        }

        assertThat(rowsBefore)
                .as("every scan recorded after the partial syncs")
                .isEqualTo(all.size());
        assertThat(totalRows(all)).as("pure resends add no row").isEqualTo(all.size());
        for (var ticket : tickets) {
            var scans = scansOfTicket.get(ticket.registrationId());
            assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
            assertThat(winningScan(ticket.registrationId())).isEqualTo(postgresWinner(scans));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM check_in_scans WHERE registration_id = ? AND outcome = 'ACCEPTED'",
                                    Integer.class,
                                    ticket.registrationId()))
                    .isEqualTo(1);
            assertThat(scanRows(ticket.registrationId())).isEqualTo(PER_TICKET);
        }
        var unresolved =
                jdbc.queryForObject(
                        "SELECT count(*) FROM check_in_scans WHERE registration_id IS NULL AND id = ANY (?::uuid[])",
                        Integer.class,
                        (Object) all.stream().map(s -> s.id().toString()).toArray(String[]::new));
        assertThat(unresolved).as("the 5 unknown codes and the 5 rejected scans").isEqualTo(10);
    }

    @Test
    @DisplayName(
            "a registration that fails inside a batch costs nothing: the others are kept and the batch can be sent again")
    void aFailureInsideABatch_losesNothing() throws Exception {
        var workshop = fixtures.aWorkshop();
        var ok1 = fixtures.aConfirmedTicket(workshop);
        var bad = fixtures.aConfirmedTicket(workshop);
        var ok2 = fixtures.aConfirmedTicket(workshop);
        var s1 = verified(ok1, T0, "dev-1");
        var sBad = verified(bad, T0, "dev-1");
        var s2 = verified(ok2, T0, "dev-1");
        var deviceRejected = rejectedBy(workshop, T0, "null");
        var batch = List.of(s1, sBad, s2, deviceRejected);
        jdbc.execute(
                "CREATE OR REPLACE FUNCTION test_failing_scan_insert() RETURNS trigger AS $$"
                        + " BEGIN IF NEW.registration_id = '"
                        + bad.registrationId()
                        + "'::uuid THEN"
                        + " RAISE EXCEPTION 'simulated failure'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute(
                "CREATE TRIGGER test_failing_scan_insert BEFORE INSERT ON check_in_scans"
                        + " FOR EACH ROW EXECUTE FUNCTION test_failing_scan_insert()");

        var failed = syncAs(staff, batch);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(winningScan(ok1.registrationId())).isEqualTo(s1.id());
        assertThat(winningScan(ok2.registrationId())).isEqualTo(s2.id());
        assertThat(scanRows(bad.registrationId()))
                .as("the failed registration left nothing half-done")
                .isZero();
        assertThat(attendanceRows(bad.registrationId())).isZero();
        assertThat(storedOutcome(deviceRejected.id())).isEqualTo("REJECTED_INVALID");

        jdbc.execute("DROP TRIGGER test_failing_scan_insert ON check_in_scans");
        var retried = outcomes(syncAs(staff, batch));

        assertThat(retried)
                .containsEntry(s1.id(), "ACCEPTED")
                .containsEntry(sBad.id(), "ACCEPTED")
                .containsEntry(s2.id(), "ACCEPTED")
                .containsEntry(deviceRejected.id(), "REJECTED_INVALID");
        assertThat(totalRows(batch)).isEqualTo(4);
        assertThat(winningScan(bad.registrationId())).isEqualTo(sBad.id());
    }

    private int totalRows(List<Scan> scans) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM check_in_scans WHERE id = ANY (?::uuid[])",
                Integer.class,
                (Object) scans.stream().map(s -> s.id().toString()).toArray(String[]::new));
    }

    private UUID postgresWinner(List<Scan> scans) {
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
                        + ") AS s(id, at) ORDER BY at, id LIMIT 1",
                UUID.class,
                args.toArray());
    }
}
