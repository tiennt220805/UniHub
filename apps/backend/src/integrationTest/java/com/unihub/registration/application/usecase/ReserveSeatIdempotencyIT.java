package com.unihub.registration.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.port.out.SeatLockHandle;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotency of {@code createRegistration} against real Postgres (Phase 2 checklist D1, T-IDEM,
 * M-20): the same key runs once and replays; a rejection replays too; a different body, another
 * actor, a rollback and a transient failure each behave as the contract says. Not
 * {@code @Transactional}: the key must commit with the effect, as it does in production.
 */
@SpringBootTest
class ReserveSeatIdempotencyIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

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

    @SpyBean private SeatLock seatLock;
    @SpyBean private RegistrationRepository registrations;

    private final ExecutorService threads = Executors.newFixedThreadPool(8);
    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(seatLock, registrations);
    }

    @AfterEach
    void cleanUp() {
        threads.shutdownNow();
        reset(seatLock, registrations);
        fixtures.cleanUp();
    }

    private WorkshopId saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private static ReserveSeatCommand command(UserId student, WorkshopId workshop, UUID key) {
        return new ReserveSeatCommand(
                new Actor.UserActor(student, Set.of()),
                workshop,
                key.toString(),
                RequestFingerprint.of(JSON, Map.of("workshopId", workshop.value())));
    }

    @Test
    @DisplayName(
            "the same key and body runs once and replays the original Result, timestamps included")
    void sameKeySamePayload_replaysOriginalResult() {
        var workshop = saved(aPaidWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();

        var first = reserveSeat.reserve(command(student, workshop, key));
        var replay = reserveSeat.reserve(command(student, workshop, key));

        assertThat(first).isInstanceOf(ReserveSeatResult.Reserved.class);
        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.idempotencyKeys(student)).isEqualTo(1);
        assertThat(fixtures.recordedResultType(student)).isEqualTo("Reserved");
    }

    @Test
    @DisplayName("a recorded sold-out replays as sold-out even after a seat has been freed")
    void sameKeySoldOut_replaysSoldOut() {
        var workshop = saved(aPublishedWorkshop().withCapacity(1).build());
        var holder = fixtures.aStudent();
        reserveSeat.reserve(command(holder, workshop, UUID.randomUUID()));
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();
        assertThat(reserveSeat.reserve(command(student, workshop, key)))
                .isEqualTo(new ReserveSeatResult.SoldOut());
        assertThat(fixtures.recordedResultType(student)).isEqualTo("SoldOut");

        // the seat is freed in between
        jdbc.update("DELETE FROM registrations WHERE user_id = ?", holder.value());
        jdbc.update(
                "UPDATE workshops SET seats_taken = 0, version = version + 1 WHERE id = ?",
                workshop.value());

        // same key: replays the 409, does not re-evaluate. A new key: takes the freed seat.
        assertThat(reserveSeat.reserve(command(student, workshop, key)))
                .isEqualTo(new ReserveSeatResult.SoldOut());
        assertThat(fixtures.registrationRows(workshop)).isZero();
        assertThat(reserveSeat.reserve(command(student, workshop, UUID.randomUUID())))
                .isInstanceOf(ReserveSeatResult.Confirmed.class);
    }

    @Test
    @DisplayName("the same key with another body is refused and takes nothing")
    void sameKeyDifferentPayload_isReused() {
        var first = saved(aPublishedWorkshop().withCapacity(5).build());
        var second = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();
        reserveSeat.reserve(command(student, first, key));

        assertThatThrownBy(() -> reserveSeat.reserve(command(student, second, key)))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(fixtures.seatsTaken(second)).isZero();
        assertThat(fixtures.registrationRows(second)).isZero();
    }

    @Test
    @DisplayName("the same key from another student is an independent operation")
    void sameKeyOtherActor_isIndependent() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var key = UUID.randomUUID();

        var a = reserveSeat.reserve(command(fixtures.aStudent(), workshop, key));
        var b = reserveSeat.reserve(command(fixtures.aStudent(), workshop, key));

        assertThat(a).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(b).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(b).isNotEqualTo(a);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(2);
    }

    @Test
    @DisplayName("many concurrent requests with one key: exactly one effect, all see its Result")
    void concurrentSameKey_exactlyOneEffect() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        var results = new ArrayList<CompletableFuture<ReserveSeatResult>>();
        for (int i = 0; i < 8; i++) {
            results.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                await(start);
                                return reserveSeat.reserve(command(student, workshop, key));
                            },
                            threads));
        }
        start.countDown();

        var first = results.get(0).get(30, TimeUnit.SECONDS);
        for (var result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(first).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.idempotencyKeys(student)).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "without the lock, a same-key request arriving mid-flight is in use, never a second effect")
    void concurrentSameKey_withoutTheLock_isInUse() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();
        // No coordination layer at all: the database's unique index is the only guard here.
        SeatLockHandle noop = () -> {};
        doAnswer(invocation -> noop).when(seatLock).tryAcquire(any(), any(), any());
        var firstIsInside = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var saves = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (saves.getAndIncrement() == 0) {
                                firstIsInside.countDown();
                                await(releaseFirst);
                            }
                            return invocation.callRealMethod();
                        })
                .when(registrations)
                .save(any());

        var first =
                CompletableFuture.supplyAsync(
                        () -> reserveSeat.reserve(command(student, workshop, key)), threads);
        assertThat(firstIsInside.await(10, TimeUnit.SECONDS)).isTrue();
        // the first transaction has begun the key and not committed; the second must block on it
        var second =
                CompletableFuture.supplyAsync(
                        () -> reserveSeat.reserve(command(student, workshop, key)), threads);
        waitUntilAnInsertIsBlockedOnTheKey();
        releaseFirst.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IdempotencyKeyInUseException.class);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
    }

    private void waitUntilAnInsertIsBlockedOnTheKey() throws Exception {
        for (int i = 0; i < 200; i++) {
            var blocked =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                    + " AND query ILIKE 'INSERT INTO idempotency_keys%'",
                            Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the second request never blocked on the idempotency key");
    }

    /** Not an IllegalStateException: a repository proxy would translate that one. */
    private static final class AfterTheClaim extends RuntimeException {
        AfterTheClaim() {
            super("fails after the seat was claimed");
        }
    }

    @Test
    @DisplayName("a failed business write leaves no key, and the same key then runs for real")
    void businessRollback_leavesNoKey() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();
        doThrow(new AfterTheClaim()).when(registrations).save(any());

        assertThatThrownBy(() -> reserveSeat.reserve(command(student, workshop, key)))
                .isInstanceOf(AfterTheClaim.class);

        assertThat(fixtures.idempotencyKeys(student)).isZero();
        assertThat(fixtures.seatsTaken(workshop)).isZero();
        reset(registrations);
        assertThat(reserveSeat.reserve(command(student, workshop, key)))
                .isInstanceOf(ReserveSeatResult.Confirmed.class);
    }

    @Test
    @DisplayName("a busy lock is not recorded, and the same key then executes")
    void lockUnavailable_notRecorded_retryExecutes() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();
        doThrow(new SeatLockUnavailableException(workshop))
                .when(seatLock)
                .tryAcquire(any(), any(), any());

        assertThatThrownBy(() -> reserveSeat.reserve(command(student, workshop, key)))
                .isInstanceOf(SeatLockUnavailableException.class);
        assertThat(fixtures.idempotencyKeys(student)).isZero();

        reset(seatLock);
        assertThat(reserveSeat.reserve(command(student, workshop, key)))
                .isInstanceOf(ReserveSeatResult.Confirmed.class);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("already-registered is recorded too, and replays under its own key")
    void alreadyRegistered_isRecorded() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        reserveSeat.reserve(command(student, workshop, UUID.randomUUID()));
        var key = UUID.randomUUID();

        var again = reserveSeat.reserve(command(student, workshop, key));
        var replay = reserveSeat.reserve(command(student, workshop, key));

        assertThat(again).isEqualTo(new ReserveSeatResult.AlreadyRegistered());
        assertThat(replay).isEqualTo(again);
        assertThat(fixtures.idempotencyKeys(student)).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for a latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
