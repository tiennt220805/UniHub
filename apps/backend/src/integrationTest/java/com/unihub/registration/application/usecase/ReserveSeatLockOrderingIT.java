package com.unihub.registration.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.port.out.SeatLock;
import com.unihub.registration.application.port.out.SeatLockHandle;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * The lock must outlive the transaction (Phase 2 checklist D4, AC4.3/AC4.4): the next holder has to
 * find the previous holder's committed rows, and the lock has to be let go on rollback too. The
 * lock is wrapped so each release is observed from another connection, which only ever sees
 * committed data.
 */
@SpringBootTest
class ReserveSeatLockOrderingIT extends IntegrationTestSupport {

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

    private RegistrationFixtures fixtures;

    /** What a different connection saw at the moment each lock handle was closed. */
    private final List<Integer> committedRowsAtRelease = new ArrayList<>();

    private final List<Integer> committedSeatsAtRelease = new ArrayList<>();
    private int releases;
    private final ExecutorService observer = Executors.newSingleThreadExecutor();

    /** Not an IllegalStateException: a repository proxy would translate that one. */
    private static final class AfterTheClaim extends RuntimeException {
        AfterTheClaim() {
            super("fails after the seat was claimed");
        }
    }

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(seatLock, registrations);
    }

    @AfterEach
    void cleanUp() {
        reset(seatLock, registrations);
        observer.shutdownNow();
        fixtures.cleanUp();
    }

    private WorkshopId aWorkshop() {
        var workshop = aPublishedWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private ReserveSeatCommand commandFor(WorkshopId workshop) {
        return new ReserveSeatCommand(new Actor.UserActor(fixtures.aStudent(), Set.of()), workshop);
    }

    /**
     * Wraps every handle the real lock returns so its release is observed. The observation runs on
     * another thread: on the request thread the JDBC call would join the transaction still open
     * around it and see its uncommitted rows, which is exactly the bug this test must catch.
     */
    private void observeReleases(WorkshopId workshop) {
        doAnswer(
                        invocation -> {
                            SeatLockHandle real = (SeatLockHandle) invocation.callRealMethod();
                            return (SeatLockHandle)
                                    () -> {
                                        real.close();
                                        releases++;
                                        var seen =
                                                observer.submit(
                                                        () ->
                                                                List.of(
                                                                        fixtures.registrationRows(
                                                                                workshop),
                                                                        fixtures.seatsTaken(
                                                                                workshop)));
                                        try {
                                            var committed = seen.get(10, TimeUnit.SECONDS);
                                            committedRowsAtRelease.add(committed.get(0));
                                            committedSeatsAtRelease.add(committed.get(1));
                                        } catch (Exception e) {
                                            throw new IllegalStateException(e);
                                        }
                                    };
                        })
                .when(seatLock)
                .tryAcquire(any(), any(), any());
    }

    @Test
    @DisplayName("the lock is released only after the transaction committed: the row is visible")
    void lockReleasedOnlyAfterCommit() throws Exception {
        var workshop = aWorkshop();
        observeReleases(workshop);

        reserveSeat.reserve(commandFor(workshop));

        assertThat(releases).isEqualTo(1);
        // had the lock been released before the commit, another connection would still see 0/0
        assertThat(committedRowsAtRelease).containsExactly(1);
        assertThat(committedSeatsAtRelease).containsExactly(1);
    }

    @Test
    @DisplayName("the lock is released when the transaction rolls back, and nothing is left behind")
    void lockReleasedOnRollback() {
        var workshop = aWorkshop();
        observeReleases(workshop);
        doThrow(new AfterTheClaim()).when(registrations).save(any());

        assertThatThrownBy(() -> reserveSeat.reserve(commandFor(workshop)))
                .isInstanceOf(AfterTheClaim.class);

        assertThat(releases).isEqualTo(1);
        assertThat(committedRowsAtRelease).containsExactly(0);
        assertThat(committedSeatsAtRelease).containsExactly(0);
        // and the lock really is free again: a second request is not left waiting for it
        reset(registrations);
        var again = seatLock.tryAcquire(workshop, Duration.ofMillis(100), Duration.ofSeconds(5));
        again.close();
    }
}
