package com.unihub.payment.infrastructure.out.persistence;

import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.domain.exception.LivePaymentExistsException;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Tier A payment adapter against real Postgres (backend-coding-standards.md §9.2): round trip,
 * explicit version check, the resolution of a {@code PENDING} payment, the gateway id, and the "one
 * live payment per registration" index, whose violation the adapter turns into a domain failure
 * (checklist D5) so it can never surface as a 500.
 */
@SpringBootTest
class JpaPaymentRepositoryIT extends IntegrationTestSupport {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private PaymentRepository payments;
    @Autowired private RegistrationRepository registrations;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private RegistrationFixtures fixtures;
    private RegistrationId registration;
    private WorkshopId workshopId;

    @BeforeEach
    void aRegistrationToPayFor() {
        fixtures = new RegistrationFixtures(jdbc);
        var workshop = aPaidWorkshop().build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        workshopId = fixtures.track(workshop.id());
        var held =
                aReservedRegistration()
                        .withWorkshopId(workshop.id())
                        .withUserId(fixtures.aStudent())
                        .build();
        tx.executeWithoutResult(s -> registrations.save(held));
        registration = held.id();
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private Payment pending() {
        return Payment.initiate(
                PaymentId.newId(), registration, new BigDecimal("100000.00"), "VND", NOW);
    }

    private Payment saveNew(Payment payment) {
        return tx.execute(s -> payments.save(payment));
    }

    private Payment find(PaymentId id) {
        return tx.execute(s -> payments.findById(id)).orElseThrow();
    }

    @Test
    @DisplayName("a new PENDING payment round-trips with its amount scale, at the initial version")
    void save_new_roundTrips() {
        var payment = saveNew(pending());

        assertThat(find(payment.id())).usingRecursiveComparison().isEqualTo(payment);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.amount()).isEqualByComparingTo("100000.00");
        assertThat(payment.version()).isEqualTo(Version.initial());
    }

    @Test
    @DisplayName("findById on an unknown id is an empty Optional")
    void findById_unknown_isEmpty() {
        Optional<Payment> missing = tx.execute(s -> payments.findById(PaymentId.newId()));

        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("resolving a payment persists status and resolved_at and increments the version")
    void save_resolution_incrementsVersion() {
        var saved = saveNew(pending());

        var resolved =
                tx.execute(
                        s -> {
                            var loaded = payments.findById(saved.id()).orElseThrow();
                            loaded.succeed(NOW.plusSeconds(30));
                            return payments.save(loaded);
                        });

        assertThat(resolved.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(resolved.resolvedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(resolved.version()).isEqualTo(new Version(1));
    }

    @Test
    @DisplayName("an aggregate with a stale version is refused")
    void save_staleVersion_isRefused() {
        var saved = saveNew(pending());
        var stale = find(saved.id());
        tx.executeWithoutResult(
                s -> {
                    var loaded = payments.findById(saved.id()).orElseThrow();
                    loaded.cancel(NOW);
                    payments.save(loaded);
                });
        stale.fail(NOW);

        assertThatThrownBy(() -> tx.execute(s -> payments.save(stale)))
                .isInstanceOf(OptimisticConflictException.class);
        assertThat(find(saved.id()).status()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("the gateway's id is stored with the payment and survives a reload")
    void attachedGatewayId_isPersisted() {
        var payment = saveNew(pending());
        payment.attachGatewayTransactionId("pi_123");
        tx.executeWithoutResult(s -> payments.save(payment));

        assertThat(find(payment.id()).gatewayTransactionId()).isEqualTo("pi_123");
    }

    @Test
    @DisplayName(
            "PENDING and SUCCEEDED count as live; every other status leaves the registration free")
    void existsLiveFor_byStatus() {
        assertThat(payments.existsLiveFor(registration)).isFalse();

        var payment = saveNew(pending());
        assertThat(payments.existsLiveFor(registration)).isTrue();

        payment.succeed(NOW);
        tx.executeWithoutResult(s -> payments.save(payment));
        assertThat(payments.existsLiveFor(registration)).isTrue();

        for (var status : new String[] {"FAILED", "CANCELLED", "REFUND_REQUIRED", "REFUNDED"}) {
            jdbc.update(
                    "UPDATE payments SET status = ? WHERE id = ?", status, payment.id().value());
            assertThat(payments.existsLiveFor(registration)).as(status).isFalse();
        }
    }

    @Test
    @DisplayName(
            "a second live payment for the registration is a domain failure, not a data-access error")
    void secondLivePayment_isLivePaymentExists() {
        saveNew(pending());

        assertThatThrownBy(() -> saveNew(pending())).isInstanceOf(LivePaymentExistsException.class);
    }

    @Test
    @DisplayName("a closed payment does not block a new one for the same registration")
    void afterFailed_aNewPaymentIsAccepted() {
        var first = saveNew(pending());
        first.fail(NOW);
        tx.executeWithoutResult(s -> payments.save(first));

        var second = saveNew(pending());

        assertThat(second.status()).isEqualTo(PaymentStatus.PENDING);
    }

    // ---------- the reconciliation sweep's query (payment.md §6) ----------

    private Payment pendingAt(Instant initiatedAt) {
        var held =
                aReservedRegistration()
                        .withWorkshopId(workshopId)
                        .withUserId(fixtures.aStudent())
                        .build();
        tx.executeWithoutResult(s -> registrations.save(held));
        return saveNew(
                Payment.initiate(
                        PaymentId.newId(),
                        held.id(),
                        new BigDecimal("100000.00"),
                        "VND",
                        initiatedAt));
    }

    @Test
    @DisplayName(
            "the sweep's cutoff is strict: 1µs before is found, exactly on it and after are not")
    void findPendingInitiatedBefore_isStrictlyBeforeTheCutoff() {
        var cutoff = Instant.parse("2021-01-01T00:15:00Z");
        var before = pendingAt(cutoff.minus(1, ChronoUnit.MICROS));
        var on = pendingAt(cutoff);
        var after = pendingAt(cutoff.plus(1, ChronoUnit.MICROS));

        var found = payments.findPendingInitiatedBefore(cutoff, 100);

        assertThat(found).contains(before.id()).doesNotContain(on.id(), after.id());
    }

    @Test
    @DisplayName("only PENDING payments are found, oldest first, and no more than the limit")
    void findPendingInitiatedBefore_onlyPending_oldestFirst_limited() {
        var cutoff = Instant.parse("2021-01-01T00:15:00Z");
        var newest = pendingAt(cutoff.minus(1, ChronoUnit.MINUTES));
        var oldest = pendingAt(cutoff.minus(3, ChronoUnit.MINUTES));
        var middle = pendingAt(cutoff.minus(2, ChronoUnit.MINUTES));
        var resolved = pendingAt(cutoff.minus(4, ChronoUnit.MINUTES));
        resolved.fail(NOW);
        tx.executeWithoutResult(s -> payments.save(resolved));

        var all = payments.findPendingInitiatedBefore(cutoff, 100);
        var firstTwo = payments.findPendingInitiatedBefore(cutoff, 2);

        assertThat(all)
                .doesNotContain(resolved.id())
                .containsSubsequence(oldest.id(), middle.id(), newest.id());
        assertThat(firstTwo).containsExactly(oldest.id(), middle.id());
    }
}
