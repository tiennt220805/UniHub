package com.unihub.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The adversarial scenario of {@code test-design-techniques.md} §3.1 and {@code
 * performance-load.md} §2.2, as a driver the INV-06 ITs share (M-28, M-29, and M-32): N students,
 * each with a user and an {@code Idempotency-Key} of their own, fire {@code POST
 * /api/v1/registrations} at one workshop from a latch, and each one that is told {@code 503} asks
 * again with the <em>same</em> key, honouring {@code Retry-After}, until it gets a definitive
 * answer ({@code 201} or {@code 409}) or runs out of attempts.
 *
 * <p>Real HTTP over the JDK {@link HttpClient}, which opens as many connections as there are
 * requests in flight. The run reports {@link Run#maxInFlight()} so a test can assert that requests
 * really overlapped: a client that queued them would pass without ever creating contention.
 *
 * <p>The assertions are on SQL, not on the response counters (Phase 3 checklist, section 4: the
 * pass condition is SQL): {@code seats_taken <= capacity}, and {@code seats_taken} equal to the
 * rows that hold a seat.
 */
public final class RegistrationContention {
    /**
     * Phase 3 checklist D30: at most 10 sends per student, the cap of {@code performance-load.md}.
     */
    public static final int MAX_SENDS = 10;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOLD_OUT_TYPE = "sold-out";

    private RegistrationContention() {}

    /** One student's run: the very first answer, and where the retries ended. */
    public record Outcome(
            UserId student,
            int firstStatus,
            boolean firstHadRetryAfter,
            int finalStatus,
            String finalProblemType,
            int sends) {

        public boolean accepted() {
            return finalStatus == 201;
        }

        public boolean soldOut() {
            return finalStatus == 409 && finalProblemType.endsWith(SOLD_OUT_TYPE);
        }
    }

    public record Run(List<Outcome> outcomes, int maxInFlight) {

        public long accepted() {
            return outcomes.stream().filter(Outcome::accepted).count();
        }

        public long soldOut() {
            return outcomes.stream().filter(Outcome::soldOut).count();
        }

        public long firstAnswered(int status) {
            return outcomes.stream().filter(o -> o.firstStatus() == status).count();
        }
    }

    private record Response(int status, boolean retryAfter, String problemType) {}

    private static HttpClient client(ExecutorService threads) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .executor(threads)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static Response send(
            HttpClient http, String baseUrl, UserId student, WorkshopId workshop, UUID key)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/registrations"))
                        .timeout(Duration.ofSeconds(30))
                        .header(
                                "Authorization",
                                "Bearer " + TestJwts.valid(student.value(), "REGISTRATION_CREATE"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key.toString())
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"workshopId\":\"" + workshop.value() + "\"}"))
                        .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String type = "";
        if (response.statusCode() == 409 || response.statusCode() == 503) {
            type = JSON.readTree(response.body()).path("type").asText("");
        }
        return new Response(
                response.statusCode(),
                response.headers().firstValue("Retry-After").isPresent(),
                type);
    }

    /**
     * A registration made one at a time, outside the race: seeds the seats already gone before the
     * latch opens. Real registrations, not a bumped counter, so that "rows holding a seat equal
     * seats_taken" is an absolute check over the whole table.
     */
    public static void registerAlone(String baseUrl, UserId student, WorkshopId workshop)
            throws Exception {
        var threads = Executors.newSingleThreadExecutor();
        try {
            var answer = send(client(threads), baseUrl, student, workshop, UUID.randomUUID());
            assertThat(answer.status()).as("seeding registration").isEqualTo(201);
        } finally {
            threads.shutdownNow();
        }
    }

    /** All students at once; returns when every one has a definitive answer or has given up. */
    public static Run race(String baseUrl, WorkshopId workshop, List<UserId> students)
            throws Exception {
        return race(List.of(baseUrl), workshop, students);
    }

    /**
     * As above across several application instances: student {@code i} talks to {@code baseUrls[i %
     * n]}, so the instances take turns being asked (M-32).
     */
    public static Run race(List<String> baseUrls, WorkshopId workshop, List<UserId> students)
            throws Exception {
        var threads = Executors.newFixedThreadPool(students.size() + 8);
        var start = new CountDownLatch(1);
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        try {
            var http = client(threads);
            var runs = new ArrayList<CompletableFuture<Outcome>>();
            for (int i = 0; i < students.size(); i++) {
                var student = students.get(i);
                var baseUrl = baseUrls.get(i % baseUrls.size());
                runs.add(
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        start.await();
                                        return untilDefinitive(
                                                http,
                                                baseUrl,
                                                student,
                                                workshop,
                                                inFlight,
                                                maxInFlight);
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                },
                                threads));
            }
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var run : runs) {
                outcomes.add(run.get(120, TimeUnit.SECONDS));
            }
            return new Run(outcomes, maxInFlight.get());
        } finally {
            threads.shutdownNow();
        }
    }

    private static Outcome untilDefinitive(
            HttpClient http,
            String baseUrl,
            UserId student,
            WorkshopId workshop,
            AtomicInteger inFlight,
            AtomicInteger maxInFlight)
            throws Exception {
        var key = UUID.randomUUID(); // one key for every send of this student
        Response first = null;
        Response last = null;
        int sends = 0;
        while (sends < MAX_SENDS) {
            sends++;
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                last = send(http, baseUrl, student, workshop, key);
            } finally {
                inFlight.decrementAndGet();
            }
            if (first == null) {
                first = last;
            }
            if (last.status() != 503) {
                break;
            }
            // Retry-After is whole seconds, 1 for the seat lock (SeatLockUnavailableException)
            Thread.sleep(1000);
        }
        return new Outcome(
                student,
                first.status(),
                first.retryAfter(),
                last.status(),
                last.problemType(),
                sends);
    }

    /**
     * What every contention run must satisfy, whatever protection layer was doing the work:
     * classified first answers, exactly {@code remaining} winners, everyone else told "sold out",
     * and the database agreeing — never past capacity, every seat counted held by a live row.
     */
    public static void assertConverged(
            Run run,
            RegistrationFixtures fixtures,
            WorkshopId workshop,
            int capacity,
            int remaining) {
        // the first answer is one of the two documented refusals or an acceptance (§3.1)
        assertThat(run.outcomes())
                .allSatisfy(
                        outcome -> {
                            assertThat(outcome.firstStatus()).isIn(201, 409, 503);
                            if (outcome.firstStatus() == 503) {
                                assertThat(outcome.firstHadRetryAfter())
                                        .as("a 503 carries Retry-After")
                                        .isTrue();
                            }
                        });
        // after retrying with the same key every request is decided: no 5xx left, none undecided
        assertThat(run.outcomes())
                .as("every student ended on 201 or a sold-out 409")
                .allMatch(o -> o.accepted() || o.soldOut());
        assertThat(run.accepted()).as("winners").isEqualTo(remaining);
        assertThat(run.soldOut()).as("losers").isEqualTo(run.outcomes().size() - remaining);

        // the pass condition is SQL
        assertThat(fixtures.seatsTaken(workshop)).as("seats_taken").isEqualTo(capacity);
        assertThat(fixtures.seatsTaken(workshop)).isLessThanOrEqualTo(capacity);
        assertThat(fixtures.holdingRows(workshop))
                .as("registrations holding a seat (RESERVED or CONFIRMED)")
                .isEqualTo(fixtures.seatsTaken(workshop));
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(capacity);
    }
}
