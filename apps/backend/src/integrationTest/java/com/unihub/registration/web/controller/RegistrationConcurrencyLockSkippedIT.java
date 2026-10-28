package com.unihub.registration.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import com.unihub.registration.RegistrationContention;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.port.out.SeatLockHandle;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.SeatCounter;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-29, INV-06, lock-skipped variant (test-design-techniques.md §3.1 row 4; Phase 3 checklist D23):
 * the same contention as {@link RegistrationConcurrencyIT}, but the seat lock's acquisition step is
 * never called. It stands in for an application bug that bypasses the lock while Redis stays fully
 * reachable — a different question from "Redis is down" (M-34, which must be refused with 503). The
 * accept/refuse decision is then PostgreSQL's alone: the conditional {@code UPDATE ... WHERE
 * version = ? AND seats_taken < capacity}, with the CHECK behind it. 0% overbooking must still
 * hold.
 *
 * <p>The seam is a {@code @Primary} no-op {@link SeatLock} in this test's own configuration. It is
 * test-only by construction: no production property or profile turns the lock off, and an ArchUnit
 * rule keeps any other {@code SeatLock} out of {@code src/main}.
 *
 * <p>Two guards against a vacuous pass: the Redisson adapter saw <em>no</em> interaction at all
 * while Redis was reachable (AC23.1), and the optimistic version check demonstrably did work — with
 * every request racing unserialized, version conflicts were counted. A run in which nothing
 * conflicted would show that the race never happened, not that the database held.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationConcurrencyLockSkippedIT extends IntegrationTestSupport {
    private static final int CAPACITY = 10;
    private static final int SEATS_LEFT = 3;
    private static final int RACERS = 40;
    private static final String VERSION_CONFLICTS = "unihub.seat.claim.version.conflicts";

    /** The lock-acquisition step skipped: hands out a handle that holds nothing. */
    static final class SkippedSeatLock implements SeatLock {
        final AtomicInteger acquires = new AtomicInteger();

        @Override
        public SeatLockHandle tryAcquire(
                WorkshopId workshopId, Duration waitTimeout, Duration leaseTime) {
            acquires.incrementAndGet();
            return () -> {};
        }
    }

    @TestConfiguration
    static class SkipTheLock {
        @Bean
        @Primary
        SkippedSeatLock skippedSeatLock() {
            return new SkippedSeatLock();
        }
    }

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
    @Autowired private SkippedSeatLock skipped;

    /** The real Redisson adapter: reachable, but never asked. */
    @SpyBean(name = "redissonSeatLock")
    private SeatLock redissonLock;

    /**
     * The global registry has no backing registry until something attaches one (no actuator yet),
     * so the counters are no-ops and would read 0 whatever happened. This one makes them count.
     */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** Spied only to open the race window: see {@link #holdTheFirstClaimsTogether}. */
    @SpyBean private SeatCounter seatCounter;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meters);
        fixtures = new RegistrationFixtures(jdbc);
        clearInvocations(redissonLock);
        reset(seatCounter);
    }

    @AfterEach
    void cleanUp() {
        reset(seatCounter);
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

    /**
     * Makes the interleaving certain instead of likely: the first {@value #SEATS_LEFT} claims to
     * reach their UPDATE wait for each other (up to 3s, then go on regardless), so all of them hold
     * the same snapshot version when they write. Exactly one can win; the others must find the
     * version moved and be refused by the {@code WHERE version = ?} — the layer under test. It does
     * not serialize anything and takes no lock: it only widens a window that, on a fast machine, a
     * run can slip through without ever opening.
     */
    private void holdTheFirstClaimsTogether() {
        var gate = new CyclicBarrier(SEATS_LEFT);
        var arrivals = new AtomicInteger();
        var tripped = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            if (arrivals.incrementAndGet() <= SEATS_LEFT && !tripped.get()) {
                                try {
                                    gate.await(3, TimeUnit.SECONDS);
                                    tripped.set(true);
                                } catch (Exception gaveUpWaiting) {
                                    // fewer than SEATS_LEFT claims came: carry on without the gate
                                }
                            }
                            return invocation.callRealMethod();
                        })
                .when(seatCounter)
                .tryClaim(any(), any());
    }

    private void raceAndVerify(WorkshopId workshop) throws Exception {
        for (int i = 0; i < CAPACITY - SEATS_LEFT; i++) {
            RegistrationContention.registerAlone(baseUrl(), fixtures.aStudent(), workshop);
        }
        var students = new ArrayList<UserId>();
        for (int i = 0; i < RACERS; i++) {
            students.add(fixtures.aStudent());
        }
        holdTheFirstClaimsTogether();
        var acquiresBefore = skipped.acquires.get();
        var conflictsBefore = versionConflicts();

        var run = RegistrationContention.race(baseUrl(), workshop, students);

        RegistrationContention.assertConverged(run, fixtures, workshop, CAPACITY, SEATS_LEFT);
        assertThat(run.maxInFlight()).as("requests in flight at once").isGreaterThan(RACERS / 2);
        // AC23.1: the lock step ran (as the no-op) for every send, and the real lock never did
        assertThat(skipped.acquires.get() - acquiresBefore).isGreaterThanOrEqualTo(RACERS);
        verifyNoInteractions(redissonLock);
        // not vacuous: unserialized, the claims collided and the version check refused the losers
        assertThat(versionConflicts() - conflictsBefore)
                .as("version conflicts with no lock to serialize the claims")
                .isPositive();
        // and Redis was reachable all along: the lock is skipped, it is not broken
        try (var probe =
                redissonLock.tryAcquire(workshop, Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            assertThat(probe).isNotNull();
        }
    }

    @Test
    @DisplayName("free: with the lock never called, the version check and CHECK still hold")
    void acceptsExactlyRemainingSeats_whenTheLockStepIsSkipped() throws Exception {
        raceAndVerify(saved(aPublishedWorkshop().withCapacity(CAPACITY).build()));
    }

    @Test
    @DisplayName("paid: the same, over holds")
    void acceptsExactlyRemainingSeats_whenTheLockStepIsSkipped_paid() throws Exception {
        raceAndVerify(saved(aPaidWorkshop().withCapacity(CAPACITY).build()));
    }
}
