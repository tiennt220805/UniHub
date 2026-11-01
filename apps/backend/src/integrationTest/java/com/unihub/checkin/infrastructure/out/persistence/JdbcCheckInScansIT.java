package com.unihub.checkin.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.checkin.CheckInFixtures;
import com.unihub.checkin.application.port.out.Attendances;
import com.unihub.checkin.application.port.out.CheckInScans;
import com.unihub.checkin.application.port.out.NewScan;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanKey;
import com.unihub.checkin.domain.model.ScanOutcome;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL of reconciliation against a real Postgres (Phase 5 checklist task 3.3; D52; checkin.md
 * §7). Every test commits for real: the advisory lock and the unique constraints are properties of
 * transactions, which a rolled-back test cannot show.
 */
@SpringBootTest
class JdbcCheckInScansIT extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private CheckInScans scans;
    @Autowired private Attendances attendances;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private CheckInFixtures fixtures;
    private UserId staff;

    @BeforeEach
    void setUp() {
        fixtures = new CheckInFixtures(jdbc, workshops, tx);
        staff = fixtures.aStaffMember();
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private NewScan scan(UUID registration, Instant scannedAt, String device) {
        return new NewScan(
                new ScanId(UUID.randomUUID()),
                registration == null ? null : RegistrationId.of(registration),
                staff,
                device,
                scannedAt);
    }

    private String outcomeOf(ScanId id) {
        return jdbc.queryForObject(
                "SELECT outcome FROM check_in_scans WHERE id = ?", String.class, id.value());
    }

    // ---------- inserts ----------

    @Test
    @DisplayName("an unresolved scan is stored, not yet decided, and is part of the valid set")
    void insertUnresolved_isPartOfTheValidSet() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var first = scan(ticket.registrationId(), T0, "dev-1");
        var second = scan(ticket.registrationId(), T0.plusSeconds(5), "dev-2");

        var inserted = tx.execute(s -> scans.insertUnresolved(List.of(first, second)));

        assertThat(inserted).isEqualTo(2);
        assertThat(scans.findValidScans(RegistrationId.of(ticket.registrationId())))
                .containsExactlyInAnyOrder(
                        new ScanKey(first.id(), T0), new ScanKey(second.id(), T0.plusSeconds(5)));
    }

    @Test
    @DisplayName("the same id sent again inserts nothing and keeps the first content")
    void insertUnresolved_sameIdAgain_keepsTheFirstRow() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var first = scan(ticket.registrationId(), T0, "dev-1");
        var altered =
                new NewScan(
                        first.id(),
                        first.registrationId(),
                        first.scannedBy(),
                        "dev-OTHER",
                        T0.minusSeconds(60));
        tx.executeWithoutResult(s -> scans.insertUnresolved(List.of(first)));

        var inserted = tx.execute(s -> scans.insertUnresolved(List.of(altered)));

        assertThat(inserted).isZero();
        var stored = scans.findByIds(List.of(first.id())).get(first.id());
        assertThat(stored.deviceId()).isEqualTo("dev-1");
        assertThat(stored.scannedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("a rejected scan is stored REJECTED_INVALID, with or without a registration")
    void insertRejected_withAndWithoutARegistration() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var known = scan(ticket.registrationId(), T0, "dev-1");
        var unknown = scan(null, T0, "dev-1");

        var inserted = tx.execute(s -> scans.insertRejected(List.of(known, unknown)));

        assertThat(inserted).isEqualTo(2);
        assertThat(outcomeOf(known.id())).isEqualTo("REJECTED_INVALID");
        assertThat(outcomeOf(unknown.id())).isEqualTo("REJECTED_INVALID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT registration_id FROM check_in_scans WHERE id = ?",
                                UUID.class,
                                unknown.id().value()))
                .isNull();
    }

    @Test
    @DisplayName("a rejected scan is outside the valid set and sent again it changes nothing")
    void insertRejected_isOutsideTheValidSet_andIdempotent() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var rejected = scan(ticket.registrationId(), T0, "dev-1");
        tx.executeWithoutResult(s -> scans.insertRejected(List.of(rejected)));

        var again = tx.execute(s -> scans.insertRejected(List.of(rejected)));

        assertThat(again).isZero();
        assertThat(scans.findValidScans(RegistrationId.of(ticket.registrationId()))).isEmpty();
    }

    @Test
    @DisplayName("findByIds returns what is stored, and leaves out ids it has never seen")
    void findByIds_returnsStoredScans() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var stored = scan(ticket.registrationId(), T0, "dev-1");
        tx.executeWithoutResult(s -> scans.insertUnresolved(List.of(stored)));

        var found = scans.findByIds(List.of(stored.id(), new ScanId(UUID.randomUUID())));

        assertThat(found).containsOnlyKeys(stored.id());
        var row = found.get(stored.id());
        assertThat(row.registrationId()).isEqualTo(RegistrationId.of(ticket.registrationId()));
        assertThat(row.scannedBy()).isEqualTo(staff);
        assertThat(row.deviceId()).isEqualTo("dev-1");
        assertThat(row.scannedAt()).isEqualTo(T0);
    }

    // ---------- outcomes ----------

    @Test
    @DisplayName("outcomes are written for the scans that changed, and updated_at moves only then")
    void updateOutcomes_touchesOnlyWhatChanged() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var winner = scan(ticket.registrationId(), T0, "dev-1");
        var loser = scan(ticket.registrationId(), T0.plusSeconds(5), "dev-2");
        tx.executeWithoutResult(s -> scans.insertUnresolved(List.of(winner, loser)));

        tx.executeWithoutResult(
                s ->
                        scans.updateOutcomes(
                                Map.of(
                                        winner.id(), ScanOutcome.ACCEPTED,
                                        loser.id(), ScanOutcome.REJECTED_DUPLICATE)));

        assertThat(outcomeOf(winner.id())).isEqualTo("ACCEPTED");
        assertThat(outcomeOf(loser.id())).isEqualTo("REJECTED_DUPLICATE");
        assertThat(updatedAtMovedPastCreation(loser.id()))
                .as("the loser was already REJECTED_DUPLICATE: nothing to revise")
                .isFalse();
        assertThat(updatedAtMovedPastCreation(winner.id())).isTrue();
    }

    @Test
    @DisplayName("a scan judged invalid is never moved to another outcome")
    void updateOutcomes_neverTouchesAnInvalidScan() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var invalid = scan(ticket.registrationId(), T0, "dev-1");
        tx.executeWithoutResult(s -> scans.insertRejected(List.of(invalid)));

        tx.executeWithoutResult(
                s -> scans.updateOutcomes(Map.of(invalid.id(), ScanOutcome.ACCEPTED)));

        assertThat(outcomeOf(invalid.id())).isEqualTo("REJECTED_INVALID");
    }

    private boolean updatedAtMovedPastCreation(ScanId id) {
        return jdbc.queryForObject(
                "SELECT updated_at > created_at FROM check_in_scans WHERE id = ?",
                Boolean.class,
                id.value());
    }

    // ---------- the lock ----------

    @Test
    @DisplayName(
            "the advisory lock holds back another transaction on the same registration until"
                    + " commit, and does not hold back a different registration")
    void lock_serializesPerRegistration() throws Exception {
        var workshop = fixtures.aWorkshop();
        var mine = RegistrationId.of(fixtures.aConfirmedTicket(workshop).registrationId());
        var other = RegistrationId.of(fixtures.aConfirmedTicket(workshop).registrationId());
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var holder =
                    pool.submit(
                            () ->
                                    tx.execute(
                                            s -> {
                                                scans.lockRegistration(mine);
                                                holding.countDown();
                                                await(release);
                                                return null;
                                            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            var sameRegistration =
                    pool.submit(
                            () ->
                                    tx.execute(
                                            s -> {
                                                scans.lockRegistration(mine);
                                                return System.nanoTime();
                                            }));
            var otherRegistration =
                    pool.submit(
                            () ->
                                    tx.execute(
                                            s -> {
                                                scans.lockRegistration(other);
                                                return System.nanoTime();
                                            }));

            otherRegistration.get(5, TimeUnit.SECONDS);
            Thread.sleep(500);
            assertThat(sameRegistration.isDone())
                    .as("still waiting while the first transaction holds the lock")
                    .isFalse();

            var releasedAt = System.nanoTime();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            var acquiredAt = sameRegistration.get(5, TimeUnit.SECONDS);
            assertThat(acquiredAt).isGreaterThan(releasedAt);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- attendances ----------

    @Test
    @DisplayName("the attendance follows the winner: one row, updated when another scan overtakes")
    void attendance_followsTheWinner_inOneRow() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var registration = RegistrationId.of(ticket.registrationId());
        var late = scan(ticket.registrationId(), T0.plusSeconds(60), "dev-1");
        var early = scan(ticket.registrationId(), T0, "dev-2");
        tx.executeWithoutResult(s -> scans.insertUnresolved(List.of(late, early)));

        tx.executeWithoutResult(
                s -> attendances.upsert(registration, late.id(), T0.plusSeconds(60)));
        tx.executeWithoutResult(s -> attendances.upsert(registration, early.id(), T0));

        assertThat(
                        jdbc.queryForList(
                                "SELECT winning_scan_id FROM attendances WHERE registration_id = ?",
                                UUID.class,
                                ticket.registrationId()))
                .containsExactly(early.id().value());
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT checked_in_at FROM attendances WHERE registration_id = ?",
                                        java.sql.Timestamp.class,
                                        ticket.registrationId())
                                .toInstant())
                .isEqualTo(T0);
    }

    @Test
    @DisplayName("upserting the same winner again leaves updated_at alone")
    void attendance_sameWinner_isNotRevised() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var registration = RegistrationId.of(ticket.registrationId());
        var only = scan(ticket.registrationId(), T0, "dev-1");
        tx.executeWithoutResult(s -> scans.insertUnresolved(List.of(only)));
        tx.executeWithoutResult(s -> attendances.upsert(registration, only.id(), T0));

        tx.executeWithoutResult(s -> attendances.upsert(registration, only.id(), T0));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT updated_at = created_at FROM attendances"
                                        + " WHERE registration_id = ?",
                                Boolean.class,
                                ticket.registrationId()))
                .isTrue();
    }

    @Test
    @DisplayName("a raw second attendance for the same registration is refused by the database")
    void attendance_uniqueConstraint_isTheBackstop() {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var registration = RegistrationId.of(ticket.registrationId());
        var first = scan(ticket.registrationId(), T0, "dev-1");
        var second = scan(ticket.registrationId(), T0.plusSeconds(1), "dev-2");
        tx.executeWithoutResult(s -> scans.insertUnresolved(List.of(first, second)));
        tx.executeWithoutResult(s -> attendances.upsert(registration, first.id(), T0));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO attendances (registration_id, winning_scan_id,"
                                                + " checked_in_at) VALUES (?, ?, ?)",
                                        ticket.registrationId(),
                                        second.id().value(),
                                        java.sql.Timestamp.from(T0)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("attendances_registration_id_key");
    }
}
