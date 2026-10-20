package com.unihub.registration.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

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
import com.unihub.workshop.api.SeatInventory;
import com.unihub.workshop.application.command.FieldChange;
import com.unihub.workshop.application.command.UpdateWorkshopCommand;
import com.unihub.workshop.application.port.in.UpdateWorkshopUseCase;
import com.unihub.workshop.application.port.out.SeatCounter;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seat claim against real Postgres (Phase 2 checklist D3, T-SEAT). Deliberately not
 * {@code @Transactional}: the retry loop opens a new transaction per attempt, which a test-managed
 * transaction would hide. The "recorded"/"not recorded" halves of the idempotency story arrive with
 * checklist 3.3; here the assertion is that a refused or exhausted claim leaves no trace.
 */
@SpringBootTest
class SeatClaimIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private ReserveSeatUseCase reserveSeat;
    @Autowired private UpdateWorkshopUseCase updateWorkshop;
    @Autowired private SeatInventory seatInventory;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    /** Spied so a test can make an organizer edit land between the read and the claim UPDATE. */
    @SpyBean private SeatCounter seatCounter;

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
        return new ReserveSeatCommand(new Actor.UserActor(student, Set.of()), workshop);
    }

    private ReserveSeatResult reserve(WorkshopId workshop) {
        return reserveSeat.reserve(commandFor(workshop, fixtures.aStudent()));
    }

    /**
     * What an organizer edit does to the row: bump the version, leave seats_taken alone. Run on
     * another thread, hence another connection and its own committed transaction — on the claim's
     * own thread the JDBC call would join the claim's transaction and be rolled back with it.
     */
    private void bumpVersionLikeAnOrganizerEdit(WorkshopId workshop) throws Exception {
        threads.submit(
                        () ->
                                jdbc.update(
                                        "UPDATE workshops SET version = version + 1 WHERE id = ?",
                                        workshop.value()))
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a free workshop confirms at once: a registration row and one seat, together")
    void free_isConfirmedAndTakesASeat() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());

        var result = reserve(workshop);

        assertThat(result).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("a paid workshop holds the seat: RESERVED with a hold, seat taken")
    void paid_isReservedAndTakesASeat() {
        var workshop = saved(aPaidWorkshop().withCapacity(5).build());

        var result = reserve(workshop);

        assertThat(result).isInstanceOf(ReserveSeatResult.Reserved.class);
        assertThat(((ReserveSeatResult.Reserved) result).holdExpiresAt()).isNotNull();
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("a full workshop is SoldOut, and nothing is written")
    void full_isSoldOut() {
        var workshop = saved(aPublishedWorkshop().withCapacity(1).build());
        reserve(workshop);

        var result = reserve(workshop);

        assertThat(result).isEqualTo(new ReserveSeatResult.SoldOut());
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("a draft workshop is not open for registration, and nothing is written")
    void draft_isNotOpen() {
        var workshop =
                saved(com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop().build());

        assertThat(reserve(workshop)).isEqualTo(new ReserveSeatResult.NotOpenForRegistration());
        assertThat(fixtures.seatsTaken(workshop)).isZero();
        assertThat(fixtures.registrationRows(workshop)).isZero();
    }

    @Test
    @DisplayName("a student who already holds a seat is AlreadyRegistered and takes no second seat")
    void secondRegistration_isAlreadyRegistered() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        reserveSeat.reserve(commandFor(workshop, student));

        var result = reserveSeat.reserve(commandFor(workshop, student));

        assertThat(result).isEqualTo(new ReserveSeatResult.AlreadyRegistered());
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("an organizer edit that wins the version race is retried, and the claim succeeds")
    void versionConflictFromOrganizerEdit_retriesAndSucceeds() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var versionBefore = fixtures.workshopVersion(workshop);
        var edits = new AtomicInteger();
        // The edit lands after the claim has read the version and before its UPDATE: that attempt
        // must lose, and only that one.
        doAnswer(
                        invocation -> {
                            if (edits.getAndIncrement() == 0) {
                                bumpVersionLikeAnOrganizerEdit(workshop);
                            }
                            return invocation.callRealMethod();
                        })
                .when(seatCounter)
                .tryClaim(any(), any());

        var result = reserve(workshop);

        assertThat(result).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(edits.get()).isEqualTo(2);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        // the edit's bump plus the claim's own
        assertThat(fixtures.workshopVersion(workshop)).isEqualTo(versionBefore + 2);
    }

    @Test
    @DisplayName(
            "when every attempt loses the version race the answer is 503 and nothing is written")
    void retriesExhausted_isUnavailable_andLeavesNoTrace() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        doAnswer(
                        invocation -> {
                            bumpVersionLikeAnOrganizerEdit(workshop);
                            return invocation.callRealMethod();
                        })
                .when(seatCounter)
                .tryClaim(any(), any());

        assertThatThrownBy(() -> reserve(workshop))
                .isInstanceOf(SeatTemporarilyUnavailableException.class);

        assertThat(fixtures.seatsTaken(workshop)).isZero();
        assertThat(fixtures.registrationRows(workshop)).isZero();
    }

    @Test
    @DisplayName("claims and organizer edits racing: every seat counted is a registration row")
    void concurrentClaimsAndEdits_keepSeatsConsistent() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(30).build());
        var students = new ArrayList<UserId>();
        for (int i = 0; i < 12; i++) {
            students.add(fixtures.aStudent());
        }
        var start = new CountDownLatch(1);
        var results = new ArrayList<CompletableFuture<Object>>();
        for (var student : students) {
            results.add(
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
        var edits = new ArrayList<CompletableFuture<Object>>();
        for (int i = 0; i < 6; i++) {
            var title = "Renamed " + i;
            edits.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                await(start);
                                try {
                                    return updateWorkshop.update(
                                            UpdateWorkshopCommand.builder(
                                                            new Actor.UserActor(
                                                                    UserId.newId(), Set.of()),
                                                            workshop.value())
                                                    .title(FieldChange.to(title))
                                                    .build());
                                } catch (
                                        com.unihub.shared.domain.OptimisticConflictException
                                                refused) {
                                    return refused;
                                }
                            },
                            threads));
        }
        start.countDown();

        List<Object> outcomes = new ArrayList<>();
        for (var result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        for (var edit : edits) {
            edit.get(30, TimeUnit.SECONDS);
        }

        var registered =
                outcomes.stream().filter(ReserveSeatResult.Confirmed.class::isInstance).count();
        // edits cost a few retries at most; they must not starve every claim
        assertThat(registered).isPositive();
        // seats_taken is exactly the number of claims that succeeded, whatever the edits did
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo((int) registered);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo((int) registered);
        assertThat(outcomes)
                .allMatch(
                        outcome ->
                                outcome instanceof ReserveSeatResult.Confirmed
                                        || outcome instanceof SeatTemporarilyUnavailableException);
    }

    @Test
    @DisplayName("more concurrent students than seats: exactly the capacity succeed, never more")
    void concurrentClaims_neverExceedCapacity() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(3).build());
        var start = new CountDownLatch(1);
        var results = new ArrayList<CompletableFuture<ReserveSeatResult>>();
        for (int i = 0; i < 10; i++) {
            var student = fixtures.aStudent();
            results.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                await(start);
                                return reserveSeat.reserve(commandFor(workshop, student));
                            },
                            threads));
        }
        start.countDown();

        var confirmed = 0;
        var soldOut = 0;
        for (var result : results) {
            var outcome = result.get(30, TimeUnit.SECONDS);
            confirmed += outcome instanceof ReserveSeatResult.Confirmed ? 1 : 0;
            soldOut += outcome instanceof ReserveSeatResult.SoldOut ? 1 : 0;
        }

        assertThat(confirmed).isEqualTo(3);
        assertThat(soldOut).isEqualTo(7);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(3);
    }

    @Test
    @DisplayName("an organizer edit never writes seats_taken, even from a stale aggregate (C15)")
    void organizerEdit_neverWritesSeatsTaken() {
        var workshop = saved(aPaidWorkshop().withCapacity(10).build());

        tx.executeWithoutResult(
                s -> {
                    var loaded = workshops.findById(workshop).orElseThrow();
                    // A seat claim lands between the load and the save, without bumping the version
                    // (the version check would otherwise reject the edit before the column
                    // matters).
                    jdbc.update(
                            "UPDATE workshops SET seats_taken = seats_taken + 1 WHERE id = ?",
                            workshop.value());
                    loaded.revise(
                            "Renamed",
                            loaded.description(),
                            loaded.speakerName(),
                            loaded.speakerBio(),
                            loaded.location(),
                            loaded.capacity(),
                            loaded.price());
                    workshops.save(loaded);
                });

        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("claiming or releasing a seat outside a transaction is refused (MANDATORY)")
    void seatInventory_outsideTransaction_isRefused() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());

        assertThatThrownBy(() -> seatInventory.claim(workshop))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> seatInventory.release(workshop))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(fixtures.seatsTaken(workshop)).isZero();
    }

    @Test
    @DisplayName("release gives the seat back inside the caller's transaction")
    void release_returnsTheSeat() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        reserve(workshop);

        tx.executeWithoutResult(s -> seatInventory.release(workshop));

        assertThat(fixtures.seatsTaken(workshop)).isZero();
    }

    @Test
    @DisplayName("a rolled-back caller transaction takes the claimed seat back with it")
    void claim_rolledBackWithTheCaller() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        s -> {
                                            seatInventory.claim(workshop);
                                            throw new IllegalStateException("after the claim");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fixtures.seatsTaken(workshop)).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
