package com.unihub.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.MultiInstanceHarness;
import com.unihub.shared.infrastructure.MultiInstanceHarness.Locking;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * M-46 / M-52, the concurrency half (INV-12; Phase 5 checklist task 3.8, D52, AC52.2): batches from
 * several devices for one ticket, sent at the same moment, still end with exactly one {@code
 * ACCEPTED} scan and an attendance that agrees with it. REQ-2.9 says the server accepts batches
 * from many devices concurrently.
 *
 * <p>Two things are measured so the test cannot pass by luck. The calls' intervals (start of the
 * HTTP request to the end of the response, taken around each call) must overlap, or the test fails
 * itself. And the window in which a lock matters is widened on purpose: a trigger in the test
 * database sleeps inside every insert into {@code check_in_scans}, so two unlocked transactions are
 * certain to be in flight together, each seeing only its own scan. With the advisory lock the
 * second batch waits for the first, which shows as a total time of at least two sleeps. The trigger
 * exists only while a test runs and is dropped after it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationConcurrencyIT extends CheckInITSupport {
    private static final Duration SLEEP = Duration.ofMillis(400);
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @AfterEach
    void dropTheSlowTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_slow_scan_insert ON check_in_scans");
        jdbc.execute("DROP FUNCTION IF EXISTS test_slow_scan_insert()");
    }

    /** Every insert into check_in_scans takes this long, inside the inserting transaction. */
    private void slowInserts(Duration sleep) {
        jdbc.execute(
                "CREATE OR REPLACE FUNCTION test_slow_scan_insert() RETURNS trigger AS $$"
                        + " BEGIN PERFORM pg_sleep("
                        + sleep.toMillis() / 1000.0
                        + "); RETURN NEW; END;"
                        + " $$ LANGUAGE plpgsql");
        jdbc.execute("DROP TRIGGER IF EXISTS test_slow_scan_insert ON check_in_scans");
        jdbc.execute(
                "CREATE TRIGGER test_slow_scan_insert BEFORE INSERT ON check_in_scans"
                        + " FOR EACH ROW EXECUTE FUNCTION test_slow_scan_insert()");
    }

    /** One call, with the time it took on the caller's clock. */
    private record Timed(int status, long startNanos, long endNanos) {}

    private Timed call(String baseUrl, String token, String body) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/checkin/sync"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        var start = System.nanoTime();
        var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new Timed(response.statusCode(), start, System.nanoTime());
    }

    /** Releases all calls together after a latch and returns how long each took. */
    private List<Timed> together(List<Callable<Timed>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var ready = new CountDownLatch(calls.size());
            var go = new CountDownLatch(1);
            var futures =
                    calls.stream()
                            .map(
                                    c ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        go.await();
                                                        return c.call();
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var results = new ArrayList<Timed>();
            for (var f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertOverlapping(List<Timed> calls) {
        var lastStart = calls.stream().mapToLong(Timed::startNanos).max().orElseThrow();
        var firstEnd = calls.stream().mapToLong(Timed::endNanos).min().orElseThrow();
        assertThat(lastStart)
                .as("every call began before any call ended: otherwise nothing was concurrent")
                .isLessThan(firstEnd);
        calls.forEach(c -> assertThat(c.status()).isEqualTo(200));
    }

    private static Duration totalTime(List<Timed> calls) {
        return Duration.ofNanos(
                calls.stream().mapToLong(Timed::endNanos).max().orElseThrow()
                        - calls.stream().mapToLong(Timed::startNanos).min().orElseThrow());
    }

    private void assertOneWinner(CheckInFixtures.Ticket ticket, List<Scan> scans) {
        var accepted =
                jdbc.queryForList(
                        "SELECT id FROM check_in_scans WHERE registration_id = ? AND outcome = 'ACCEPTED'",
                        UUID.class,
                        ticket.registrationId());
        assertThat(accepted).as("exactly one ACCEPTED scan").hasSize(1);
        assertThat(attendanceRows(ticket.registrationId())).isEqualTo(1);
        assertThat(winningScan(ticket.registrationId()))
                .as("the attendance names the ACCEPTED scan")
                .isEqualTo(accepted.get(0));
        var earliest =
                scans.stream().min(java.util.Comparator.comparing(Scan::scannedAt)).orElseThrow();
        assertThat(accepted.get(0)).as("and it is the earliest scan").isEqualTo(earliest.id());
        scans.stream()
                .filter(s -> !s.id().equals(earliest.id()))
                .forEach(s -> assertThat(storedOutcome(s.id())).isEqualTo("REJECTED_DUPLICATE"));
    }

    @Test
    @Timeout(120)
    @DisplayName(
            "AC52.2: two batches for one ticket sent together end with one ACCEPTED, in step with the attendance")
    void twoBatches_oneRegistration_oneAccepted() throws Exception {
        slowInserts(SLEEP);
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var other = fixtures.aStaffMember();
        var late = verified(ticket, T0.plusSeconds(5), "dev-1");
        var early = verified(ticket, T0, "dev-2");
        var url = "http://localhost:" + port;

        var calls =
                together(
                        List.of(
                                () -> call(url, token(staff), batch(List.of(late))),
                                () -> call(url, token(other), batch(List.of(early)))));

        assertOverlapping(calls);
        assertThat(totalTime(calls))
                .as("the second batch waited for the first one's lock: two sleeps, not one")
                .isGreaterThanOrEqualTo(SLEEP.multipliedBy(2).minusMillis(100));
        assertOneWinner(ticket, List.of(late, early));
    }

    @Test
    @Timeout(180)
    @DisplayName(
            "eight devices at once, each with two scans of the same ticket: still one ACCEPTED")
    void eightBatches_oneRegistration_oneAccepted() throws Exception {
        slowInserts(Duration.ofMillis(150));
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var url = "http://localhost:" + port;
        var all = new ArrayList<Scan>();
        var calls = new ArrayList<Callable<Timed>>();
        for (var device = 0; device < 8; device++) {
            var user = fixtures.aStaffMember();
            var first = verified(ticket, T0.plusSeconds(100 - device * 10L), "dev-" + device);
            var second = verified(ticket, T0.plusSeconds(200 + device), "dev-" + device);
            all.add(first);
            all.add(second);
            var body = batch(List.of(second, first));
            calls.add(() -> call(url, token(user), body));
        }

        var timed = together(calls);

        assertOverlapping(timed);
        assertOneWinner(ticket, all);
    }

    @Test
    @Timeout(240)
    @DisplayName(
            "across two application instances: the same, one ACCEPTED, in step with the attendance")
    void twoInstances_oneRegistration_oneAccepted() throws Exception {
        slowInserts(SLEEP);
        try (var instances = MultiInstanceHarness.start(2, Locking.REDISSON, Duration.ZERO)) {
            var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
            var other = fixtures.aStaffMember();
            var late = verified(ticket, T0.plusSeconds(5), "dev-1");
            var early = verified(ticket, T0, "dev-2");
            var first = "http://localhost:" + instances.port(0);
            var second = "http://localhost:" + instances.port(1);
            assertThat(first).isNotEqualTo(second);

            var calls =
                    together(
                            List.of(
                                    () -> call(first, token(staff), batch(List.of(late))),
                                    () -> call(second, token(other), batch(List.of(early)))));

            assertOverlapping(calls);
            assertThat(totalTime(calls))
                    .as("the second instance's batch waited for the first one's lock")
                    .isGreaterThanOrEqualTo(SLEEP.multipliedBy(2).minusMillis(100));
            assertOneWinner(ticket, List.of(late, early));
        }
    }

    @Test
    @Timeout(240)
    @DisplayName(
            "without the artificial delay, 20 rounds of two racing batches each end with one ACCEPTED")
    void unslowedRounds_alwaysOneAccepted() throws Exception {
        var workshop = fixtures.aWorkshop();
        var other = fixtures.aStaffMember();
        var url = "http://localhost:" + port;
        for (var round = 1; round <= 20; round++) {
            var ticket = fixtures.aConfirmedTicket(workshop);
            var late = verified(ticket, T0.plusSeconds(5), "dev-1");
            var early = verified(ticket, T0, "dev-2");
            var inRound = round;

            var calls =
                    together(
                            List.of(
                                    () -> call(url, token(staff), batch(List.of(late))),
                                    () -> call(url, token(other), batch(List.of(early)))));

            calls.forEach(c -> assertThat(c.status()).as("round %d", inRound).isEqualTo(200));
            assertOneWinner(ticket, List.of(late, early));
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("batches for different tickets do not wait for each other")
    void differentTickets_doNotBlockEachOther() throws Exception {
        slowInserts(SLEEP);
        var workshop = fixtures.aWorkshop();
        var one = fixtures.aConfirmedTicket(workshop);
        var two = fixtures.aConfirmedTicket(workshop);
        var other = fixtures.aStaffMember();
        var a = verified(one, T0, "dev-1");
        var b = verified(two, T0, "dev-2");
        var url = "http://localhost:" + port;

        var calls =
                together(
                        List.of(
                                () -> call(url, token(staff), batch(List.of(a))),
                                () -> call(url, token(other), batch(List.of(b)))));

        assertOverlapping(calls);
        assertThat(totalTime(calls))
                .as("two registrations, two locks: the sleeps run side by side")
                .isLessThan(SLEEP.multipliedBy(2).minusMillis(50));
        assertThat(winningScan(one.registrationId())).isEqualTo(a.id());
        assertThat(winningScan(two.registrationId())).isEqualTo(b.id());
    }
}
