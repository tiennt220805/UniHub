package com.unihub.registration.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.unihub.registration.RegistrationContention;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-28, INV-06, full-stack variant (test-design-techniques.md §3.1 rows 1-3): the Redisson seat
 * lock, the {@code workshops.version} check and the CHECK constraint all in place. Capacity is
 * {@value #CAPACITY}, {@value #SEATS_LEFT} seats are left (taken by real registrations, so the
 * absolute check "rows holding a seat equal seats_taken" is meaningful), and {@value #RACERS}
 * students — each with a user and an {@code Idempotency-Key} of their own — hit the endpoint from a
 * latch over real HTTP (see {@link RegistrationContention}).
 *
 * <p>The first answer of a loser is classified, never asserted as all-409: it is a {@code 409}
 * (reached the seat check and found the workshop full) or a {@code 503} with {@code Retry-After}
 * (lost lock contention). Retried with the same key every one of them ends at {@code 409}, and
 * exactly the remaining seats are {@code 201}. The pass condition is SQL.
 *
 * <p>Two things make this the <em>full-stack</em> variant rather than a test that happens to pass:
 * the lock really was asked for by the requests, and, with the lock serializing the claims on the
 * workshop, none of them lost the version race (no version conflict was counted).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationConcurrencyIT extends IntegrationTestSupport {
    private static final int CAPACITY = 10;
    private static final int SEATS_LEFT = 3;
    private static final int RACERS = 40;
    private static final String VERSION_CONFLICTS = "unihub.seat.claim.version.conflicts";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;

    /** The real Redisson adapter, observed. */
    @SpyBean private SeatLock seatLock;

    /**
     * The global registry has no backing registry until something attaches one (no actuator yet),
     * so the counters are no-ops and would read 0 whatever happened. This one makes them count.
     */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meters);
        fixtures = new RegistrationFixtures(jdbc);
        clearInvocations(seatLock);
    }

    @AfterEach
    void cleanUp() {
        Metrics.removeRegistry(meters);
        fixtures.cleanUp();
    }

    private double versionConflicts() {
        var counter = meters.find(VERSION_CONFLICTS).counter();
        return counter == null ? 0 : counter.count();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private WorkshopId saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private void seedTakenSeats(WorkshopId workshop) throws Exception {
        for (int i = 0; i < CAPACITY - SEATS_LEFT; i++) {
            RegistrationContention.registerAlone(baseUrl(), fixtures.aStudent(), workshop);
        }
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(CAPACITY - SEATS_LEFT);
    }

    private void raceAndVerify(WorkshopId workshop) throws Exception {
        seedTakenSeats(workshop);
        clearInvocations(seatLock);
        var students = new ArrayList<UserId>();
        for (int i = 0; i < RACERS; i++) {
            students.add(fixtures.aStudent());
        }
        var conflictsBefore = versionConflicts();

        var run = RegistrationContention.race(baseUrl(), workshop, students);

        RegistrationContention.assertConverged(run, fixtures, workshop, CAPACITY, SEATS_LEFT);
        // the requests really were concurrent: a client that serialized them proves nothing
        assertThat(run.maxInFlight()).as("requests in flight at once").isGreaterThan(RACERS / 2);
        // the lock layer was in play for every student's first send...
        verify(seatLock, atLeast(RACERS)).tryAcquire(any(), any(), any());
        // ...and, holding the workshop one claim at a time, left the version nothing to lose
        assertThat(versionConflicts() - conflictsBefore)
                .as("version conflicts while the lock serializes the claims")
                .isZero();
    }

    @Test
    @DisplayName("free: more students than seats left, exactly the remaining seats are accepted")
    void acceptsExactlyRemainingSeats_whenConcurrentRequestsExceedCapacity() throws Exception {
        raceAndVerify(saved(aPublishedWorkshop().withCapacity(CAPACITY).build()));
    }

    @Test
    @DisplayName("paid: the same race over holds, a RESERVED registration counts as a taken seat")
    void acceptsExactlyRemainingSeats_whenConcurrentPaidRequestsExceedCapacity() throws Exception {
        var workshop = saved(aPaidWorkshop().withCapacity(CAPACITY).build());

        raceAndVerify(workshop);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM registrations WHERE workshop_id = ?"
                                        + " AND status = 'RESERVED'",
                                Integer.class,
                                workshop.value()))
                .as("every accepted paid registration is a hold")
                .isEqualTo(CAPACITY);
    }
}
