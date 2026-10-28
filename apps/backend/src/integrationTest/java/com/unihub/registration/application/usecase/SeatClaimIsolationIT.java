package com.unihub.registration.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.exception.SeatTemporarilyUnavailableException;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.SeatCounter;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-31 (REQ-2.6.3, Phase 3 checklist D24): the seat claim runs at {@code READ COMMITTED}, and that
 * is enough because the decision is <em>one</em> conditional {@code UPDATE ... WHERE version = ?
 * AND seats_taken < capacity}. The reads around it (the snapshot before, the classification after a
 * zero-row UPDATE) only choose the answer; they never guard a write.
 *
 * <p>So there are two claims to prove, and neither is "the database does this": (1) the isolation
 * level actually in force <em>inside the claim's transaction</em>, read from that transaction
 * itself, and (2) that the classification read tolerates a non-repeatable read — it must see what a
 * concurrent writer committed since the snapshot, because that is what turns a stale "one seat
 * left" into the right answer ({@code SoldOut}). Under {@code REPEATABLE READ} the same
 * interleaving would show the old row forever.
 *
 * <p>Deliberately not {@code @Transactional}: the claim opens its own transaction per attempt.
 */
@SpringBootTest
class SeatClaimIsolationIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private ReserveSeatUseCase reserveSeat;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @SpyBean private SeatCounter seatCounter;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutorService threads = Executors.newFixedThreadPool(16);
    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(seatCounter);
    }

    @AfterEach
    void cleanUp() {
        threads.shutdownNow();
        reset(seatCounter);
        fixtures.cleanUp();
    }

    private WorkshopId saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private ReserveSeatCommand commandFor(WorkshopId workshop, UserId student) {
        return new ReserveSeatCommand(
                new Actor.UserActor(student, Set.of()),
                workshop,
                UUID.randomUUID().toString(),
                RequestFingerprint.of(JSON, Map.of("workshopId", workshop.value())));
    }

    private String isolationHere() {
        return jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class);
    }

    /** Runs on another thread, hence another connection and its own committed transaction. */
    private void committedElsewhere(Runnable write) throws Exception {
        threads.submit(write).get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("AC24.1: the claim's own transaction reads and writes at read committed")
    void theClaimTransaction_isReadCommitted() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var seen = new AtomicReference<String>();
        var inTransaction = new AtomicReference<Boolean>();
        doAnswer(
                        invocation -> {
                            // on the claim's thread: this statement joins the claim's transaction
                            seen.set(isolationHere());
                            inTransaction.set(
                                    org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive());
                            return invocation.callRealMethod();
                        })
                .when(seatCounter)
                .tryClaim(any(), any());

        var result = reserveSeat.reserve(commandFor(workshop, fixtures.aStudent()));

        assertThat(result).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(inTransaction.get()).as("tryClaim ran inside the claim transaction").isTrue();
        assertThat(seen.get()).isEqualTo("read committed");
    }

    @Test
    @DisplayName(
            "AC24.2: the last seat is taken between the snapshot and the UPDATE: SoldOut, not a"
                    + " stale retry loop")
    void lastSeatTakenMeanwhile_isClassifiedFromAFreshRead() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(1).build());
        var student = fixtures.aStudent();
        var interleaved = new AtomicReference<Boolean>(false);
        // The snapshot has been read (1 seat free, version v). Now another claim commits the last
        // seat and moves the version, so this UPDATE matches no row. The re-read that follows must
        // see that committed change: a non-repeatable read, which read committed allows and the
        // answer depends on.
        doAnswer(
                        invocation -> {
                            if (interleaved.compareAndSet(false, true)) {
                                committedElsewhere(
                                        () ->
                                                jdbc.update(
                                                        "UPDATE workshops SET seats_taken ="
                                                                + " seats_taken + 1, version ="
                                                                + " version + 1 WHERE id = ?",
                                                        workshop.value()));
                            }
                            return invocation.callRealMethod();
                        })
                .when(seatCounter)
                .tryClaim(any(), any());

        var result = reserveSeat.reserve(commandFor(workshop, student));

        assertThat(result).isEqualTo(new ReserveSeatResult.SoldOut());
        // the other writer's seat is the only one: this claim wrote nothing of its own
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.registrationRows(workshop)).isZero();
    }

    @Test
    @DisplayName(
            "AC24.2: a version bump between the snapshot and the UPDATE is retried on a fresh"
                    + " read, and the seat is taken once")
    void versionMovedMeanwhile_retriesOnAFreshSnapshot() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var interleaved = new AtomicReference<Boolean>(false);
        doAnswer(
                        invocation -> {
                            if (interleaved.compareAndSet(false, true)) {
                                committedElsewhere(
                                        () ->
                                                jdbc.update(
                                                        "UPDATE workshops SET version = version"
                                                                + " + 1 WHERE id = ?",
                                                        workshop.value()));
                            }
                            return invocation.callRealMethod();
                        })
                .when(seatCounter)
                .tryClaim(any(), any());

        var result = reserveSeat.reserve(commandFor(workshop, fixtures.aStudent()));

        // the retry's snapshot is a new statement in a new transaction and sees the bumped version
        assertThat(result).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "AC24.2: claims racing each other and version bumps: no lost update, no overbooking")
    void racingClaimsAndBumps_neitherLoseAnUpdateNorOverbook() throws Exception {
        int capacity = 6;
        var workshop = saved(aPublishedWorkshop().withCapacity(capacity).build());
        var start = new CountDownLatch(1);
        var claims = new ArrayList<CompletableFuture<Object>>();
        for (int i = 0; i < 16; i++) {
            var student = fixtures.aStudent();
            claims.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                await(start);
                                try {
                                    return reserveSeat.reserve(commandFor(workshop, student));
                                } catch (SeatTemporarilyUnavailableException exhausted) {
                                    return exhausted;
                                }
                            },
                            threads));
        }
        var bumps = new ArrayList<CompletableFuture<Integer>>();
        for (int i = 0; i < 4; i++) {
            bumps.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                await(start);
                                return jdbc.update(
                                        "UPDATE workshops SET version = version + 1 WHERE id = ?",
                                        workshop.value());
                            },
                            threads));
        }
        start.countDown();

        long confirmed = 0;
        for (var claim : claims) {
            var outcome = claim.get(60, TimeUnit.SECONDS);
            confirmed += outcome instanceof ReserveSeatResult.Confirmed ? 1 : 0;
        }
        for (var bump : bumps) {
            bump.get(60, TimeUnit.SECONDS);
        }

        // every counted seat is a registration row, and the capacity was never passed
        assertThat(fixtures.seatsTaken(workshop))
                .isEqualTo((int) confirmed)
                .isLessThanOrEqualTo(capacity);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo((int) confirmed);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
