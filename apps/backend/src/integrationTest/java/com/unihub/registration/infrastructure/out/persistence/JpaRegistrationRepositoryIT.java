package com.unihub.registration.infrastructure.out.persistence;

import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.domain.exception.AlreadyRegisteredException;
import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationBuilder;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.registration.domain.model.TestTickets;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Tier A registration adapter against real Postgres (backend-coding-standards.md §9.2, Phase 2
 * checklist D2/C8): round trip, explicit version check, the status-guarded transition and the
 * partial unique index. Real transactions, so rows are committed and cleaned up by hand.
 */
@SpringBootTest
class JpaRegistrationRepositoryIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private RegistrationRepository registrations;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private RegistrationFixtures fixtures;

    @AfterEach
    void cleanUp() {
        if (fixtures != null) {
            fixtures.cleanUp();
        }
    }

    private WorkshopId aWorkshop() {
        fixtures = fixtures == null ? new RegistrationFixtures(jdbc) : fixtures;
        var workshop = aPaidWorkshop().build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private UserId aStudent() {
        fixtures = fixtures == null ? new RegistrationFixtures(jdbc) : fixtures;
        return fixtures.aStudent();
    }

    private Registration saveNew(WorkshopId workshop, UserId student) {
        var registration =
                aReservedRegistration().withWorkshopId(workshop).withUserId(student).build();
        return tx.execute(s -> registrations.save(registration));
    }

    private boolean isActive(WorkshopId workshop, UserId student) {
        Boolean active = tx.execute(s -> registrations.existsActive(workshop, student));
        return Boolean.TRUE.equals(active);
    }

    private Registration find(RegistrationId id) {
        return tx.execute(s -> registrations.findById(id)).orElseThrow();
    }

    @Test
    @DisplayName("a new RESERVED registration round-trips with its hold, at the initial version")
    void save_new_roundTrips() {
        var registration = saveNew(aWorkshop(), aStudent());

        var loaded = find(registration.id());

        assertThat(loaded).usingRecursiveComparison().isEqualTo(registration);
        assertThat(loaded.status()).isEqualTo(RegistrationStatus.RESERVED);
        assertThat(loaded.holdExpiresAt()).isNotNull();
        assertThat(loaded.version()).isEqualTo(Version.initial());
    }

    @Test
    @DisplayName("findById on an unknown id is an empty Optional")
    void findById_unknown_isEmpty() {
        Optional<Registration> missing =
                tx.execute(s -> registrations.findById(RegistrationId.newId()));
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("an update persists and returns the incremented version")
    void save_update_incrementsVersion() {
        var saved = saveNew(aWorkshop(), aStudent());

        var updated =
                tx.execute(
                        s -> {
                            var loaded = registrations.findById(saved.id()).orElseThrow();
                            loaded.cancel();
                            return registrations.save(loaded);
                        });

        assertThat(updated.status()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(updated.version()).isEqualTo(new Version(1));
        assertThat(find(saved.id()).holdExpiresAt()).isNull();
    }

    @Test
    @DisplayName("an aggregate with a stale version is refused")
    void save_staleVersion_isRefused() {
        var saved = saveNew(aWorkshop(), aStudent());
        tx.executeWithoutResult(
                s -> {
                    var loaded = registrations.findById(saved.id()).orElseThrow();
                    loaded.cancel();
                    registrations.save(loaded);
                });

        var stale = find(saved.id());
        var staleCopy =
                Registration.restore(
                        stale.id(),
                        stale.workshopId(),
                        stale.userId(),
                        stale.ticketType(),
                        RegistrationStatus.RESERVED,
                        RegistrationBuilder.HOLD_UNTIL,
                        null,
                        null,
                        Version.initial());

        assertThatThrownBy(() -> tx.execute(s -> registrations.save(staleCopy)))
                .isInstanceOf(OptimisticConflictException.class);
    }

    @Test
    @DisplayName("the guarded transition changes status and version when the row is still RESERVED")
    void transition_fromReserved_succeeds() {
        var saved = saveNew(aWorkshop(), aStudent());

        var confirmed =
                tx.execute(
                        s -> {
                            var loaded = registrations.findById(saved.id()).orElseThrow();
                            loaded.confirm(java.time.Instant.now(), TestTickets.aUniqueTicket());
                            return registrations.transition(loaded, RegistrationStatus.RESERVED);
                        });

        assertThat(confirmed.status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(confirmed.holdExpiresAt()).isNull();
        assertThat(confirmed.version()).isEqualTo(new Version(1));
        // the same UPDATE wrote the ticket (checkin.md §4): status, ticket_code, qr_payload
        // together
        assertThat(confirmed.ticketCode()).isNotBlank();
        assertThat(confirmed.qrPayload()).startsWith("h.");
    }

    @Test
    @DisplayName("the guarded transition loses when the row is no longer in the expected status")
    void transition_fromWrongStatus_isRefused() {
        var saved = saveNew(aWorkshop(), aStudent());
        var loaded = find(saved.id());
        // expiry got there first, at the same version the confirming side loaded
        jdbc.update(
                "UPDATE registrations SET status = 'EXPIRED', hold_expires_at = NULL WHERE id = ?",
                saved.id().value());
        loaded.confirm(java.time.Instant.now(), TestTickets.aUniqueTicket());

        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                registrations.transition(
                                                        loaded, RegistrationStatus.RESERVED)))
                .isInstanceOf(OptimisticConflictException.class);
        assertThat(find(saved.id()).status()).isEqualTo(RegistrationStatus.EXPIRED);
    }

    @Test
    @DisplayName("confirm and expire racing on one RESERVED row: exactly one wins")
    void transition_race_exactlyOneWins() throws Exception {
        var saved = saveNew(aWorkshop(), aStudent());
        var barrier = new CyclicBarrier(2);

        CompletableFuture<Boolean> confirm =
                CompletableFuture.supplyAsync(() -> race(saved, barrier, true));
        CompletableFuture<Boolean> expire =
                CompletableFuture.supplyAsync(() -> race(saved, barrier, false));

        var wins =
                (confirm.get(10, TimeUnit.SECONDS) ? 1 : 0)
                        + (expire.get(10, TimeUnit.SECONDS) ? 1 : 0);
        assertThat(wins).isEqualTo(1);
        assertThat(find(saved.id()).status())
                .isIn(RegistrationStatus.CONFIRMED, RegistrationStatus.EXPIRED);
    }

    private boolean race(Registration saved, CyclicBarrier barrier, boolean confirm) {
        try {
            return Boolean.TRUE.equals(
                    tx.execute(
                            s -> {
                                var loaded = registrations.findById(saved.id()).orElseThrow();
                                await(barrier);
                                if (confirm) {
                                    loaded.confirm(
                                            java.time.Instant.now(), TestTickets.aUniqueTicket());
                                } else {
                                    loaded.expire();
                                }
                                registrations.transition(loaded, RegistrationStatus.RESERVED);
                                return true;
                            }));
        } catch (OptimisticConflictException lost) {
            return false;
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("existsActive sees RESERVED and CONFIRMED, not CANCELLED, and is per student")
    void existsActive() {
        var workshop = aWorkshop();
        var student = aStudent();
        var other = aStudent();
        var saved = saveNew(workshop, student);

        assertThat(isActive(workshop, student)).isTrue();
        assertThat(isActive(workshop, other)).isFalse();

        tx.executeWithoutResult(
                s -> {
                    var loaded = registrations.findById(saved.id()).orElseThrow();
                    loaded.cancel();
                    registrations.save(loaded);
                });
        assertThat(isActive(workshop, student)).isFalse();
    }

    @Test
    @DisplayName("a second active registration for one student and workshop is a 409, not a 500")
    void secondActiveRegistration_isAlreadyRegistered() {
        var workshop = aWorkshop();
        var student = aStudent();
        saveNew(workshop, student);

        assertThatThrownBy(() -> saveNew(workshop, student))
                .isInstanceOf(AlreadyRegisteredException.class);
    }

    @Test
    @DisplayName("after cancelling, the student may register again for the same workshop")
    void afterCancel_mayRegisterAgain() {
        var workshop = aWorkshop();
        var student = aStudent();
        var first = saveNew(workshop, student);
        tx.executeWithoutResult(
                s -> {
                    var loaded = registrations.findById(first.id()).orElseThrow();
                    loaded.cancel();
                    registrations.save(loaded);
                });

        var second = saveNew(workshop, student);

        assertThat(find(second.id()).status()).isEqualTo(RegistrationStatus.RESERVED);
    }
}
