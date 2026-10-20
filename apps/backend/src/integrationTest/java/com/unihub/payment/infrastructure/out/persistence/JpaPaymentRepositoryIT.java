package com.unihub.payment.infrastructure.out.persistence;

import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
 * explicit version check, and the resolution of a {@code PENDING} payment. Translating the
 * live-payment unique index into a business outcome belongs to the charge use case.
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

    @BeforeEach
    void aRegistrationToPayFor() {
        fixtures = new RegistrationFixtures(jdbc);
        var workshop = aPaidWorkshop().build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        fixtures.track(workshop.id());
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
}
