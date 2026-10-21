package com.unihub.payment.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.domain.exception.PaymentGatewayUnavailableException;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The charge against real Postgres with the gateway doubled (Phase 2 checklist T-PAY, T-CHARGE, D5,
 * D10): what each answer of the gateway leaves in {@code payments}, {@code idempotency_keys} and
 * the outbox, and that two live payments for one registration can never exist. Not
 * {@code @Transactional}: the property is about what is committed.
 */
@SpringBootTest
class ChargePaymentIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private ChargePaymentUseCase chargePayment;
    @Autowired private ReserveSeatUseCase reserveSeat;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @MockBean private PaymentGateway gateway;
    @SpyBean private IdempotencyStore idempotency;
    @SpyBean private PaymentRepository payments;

    private final ExecutorService threads = Executors.newFixedThreadPool(4);
    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway, idempotency, payments);
        // A fresh id per call, as the gateway gives: payments.gateway_transaction_id is UNIQUE.
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        threads.shutdownNow();
        reset(gateway, idempotency, payments);
        fixtures.cleanUp();
    }

    /** Not an IllegalStateException: a repository proxy would translate that one. */
    private static final class DatabaseDown extends RuntimeException {
        DatabaseDown() {
            super("db down");
        }
    }

    /** A student holding a RESERVED seat in a paid workshop. */
    private record Reserved(UserId student, RegistrationId registration) {}

    private Reserved aReservedStudent() {
        Workshop workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var id = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var reserved =
                (ReserveSeatResult.Reserved)
                        reserveSeat.reserve(
                                new ReserveSeatCommand(
                                        new Actor.UserActor(student, Set.of()),
                                        id,
                                        UUID.randomUUID().toString(),
                                        RequestFingerprint.of(
                                                JSON, Map.of("workshopId", id.value()))));
        return new Reserved(student, reserved.id());
    }

    private ChargePaymentCommand command(Reserved r, UUID key) {
        return new ChargePaymentCommand(
                new Actor.UserActor(r.student(), Set.of()),
                r.registration(),
                "pm_card_visa",
                key.toString(),
                RequestFingerprint.of(
                        JSON,
                        Map.of(
                                "registrationId",
                                r.registration().value(),
                                "paymentMethodToken",
                                "pm_card_visa")));
    }

    private static ChargePaymentResult.Processed processed(ChargePaymentResult result) {
        return (ChargePaymentResult.Processed) result;
    }

    // ---------- D10: the response is the real state ----------

    @Test
    @DisplayName(
            "a synchronous success is SUCCEEDED, with charge.succeeded written in the same transaction")
    void syncSuccess_isSucceeded_withTheOutboxRow() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());

        var result = processed(chargePayment.charge(command(r, UUID.randomUUID())));

        assertThat(result.status()).isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        assertThat(result.gatewayTransactionId()).startsWith("pi_");
        assertThat(result.resolvedAt()).isNotNull();
        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("SUCCEEDED");
        assertThat(fixtures.paymentOutboxRows(r.registration().value())).isEqualTo(1);
        assertThat(fixtures.chargeKeyStatus(r.student())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("forcing tx2 to roll back leaves neither a SUCCEEDED payment nor the outbox row")
    void syncSuccess_tx2Rollback_leavesNeither() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        tx.executeWithoutResult(
                s ->
                        doThrow(new DatabaseDown())
                                .when(idempotency)
                                .complete(any(), any(), any(), any(), any()));

        assertThatThrownBy(() -> chargePayment.charge(command(r, UUID.randomUUID())))
                .isInstanceOf(DatabaseDown.class);

        assertThat(fixtures.paymentStatuses(r.registration().value()))
                .as("tx2 rolled back, so the payment is still what tx1 left")
                .containsExactly("PENDING");
        assertThat(fixtures.paymentOutboxRows(r.registration().value())).isZero();
        assertThat(fixtures.chargeKeyStatus(r.student()))
                .as(
                        "the crash window of D10 constraint 5: only the reconciliation of 3.6 closes it")
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("a declined card is FAILED, no outbox row, and the same key replays FAILED")
    void syncDecline_isFailed_andReplays() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any()))
                .thenReturn(new ConfirmOutcome.Declined("generic_decline"));

        var first = processed(chargePayment.charge(command(r, key)));
        var replay = processed(chargePayment.charge(command(r, key)));

        assertThat(first.status()).isEqualTo(ChargePaymentResult.Status.FAILED);
        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.paymentOutboxRows(r.registration().value())).isZero();
        verify(gateway, times(1)).create(any(), any(), any(), any());
        verify(gateway, times(1)).confirm(any(), any());
    }

    // ---------- D5: retrying, and one live payment ----------

    @Test
    @DisplayName("after a FAILED payment a new key is accepted and the gateway is called again")
    void failedThenNewKey_isAllowed() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any()))
                .thenReturn(new ConfirmOutcome.Declined("generic_decline"))
                .thenReturn(new ConfirmOutcome.Succeeded());

        var first = processed(chargePayment.charge(command(r, UUID.randomUUID())));
        var second = processed(chargePayment.charge(command(r, UUID.randomUUID())));

        assertThat(first.status()).isEqualTo(ChargePaymentResult.Status.FAILED);
        assertThat(second.status()).isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        assertThat(fixtures.paymentStatuses(r.registration().value()))
                .containsExactly("FAILED", "SUCCEEDED");
    }

    @Test
    @DisplayName(
            "a payment cancelled by compensation does not block the same key from running for real")
    void cancelledByCompensation_thenSameKey_isAllowed() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any()))
                .thenThrow(new GatewayNotReachedException("refused", null))
                .thenReturn(new ConfirmOutcome.Succeeded());

        assertThatThrownBy(() -> chargePayment.charge(command(r, key)))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("CANCELLED");
        assertThat(fixtures.chargeKeys(r.student())).as("the key was dropped").isZero();

        var retry = processed(chargePayment.charge(command(r, key)));

        assertThat(retry.status()).isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        assertThat(fixtures.paymentStatuses(r.registration().value()))
                .containsExactly("CANCELLED", "SUCCEEDED");
    }

    @Test
    @DisplayName(
            "a second key while a payment is live is 'in progress'; the gateway is called once")
    void secondLivePayment_differentKey_isInProgress_gatewayOnce() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
        var otherKey = UUID.randomUUID();

        var first = processed(chargePayment.charge(command(r, UUID.randomUUID())));
        var second = chargePayment.charge(command(r, otherKey));
        var replayOfSecond = chargePayment.charge(command(r, otherKey));

        assertThat(first.status()).isEqualTo(ChargePaymentResult.Status.PENDING);
        assertThat(second).isEqualTo(new ChargePaymentResult.PaymentInProgress());
        assertThat(replayOfSecond).isEqualTo(second);
        verify(gateway, times(1)).create(any(), any(), any(), any());
        assertThat(fixtures.paymentStatuses(r.registration().value())).hasSize(1);
    }

    @Test
    @DisplayName(
            "two requests racing past the check: the index lets one in, the other is 'in progress', never a 500")
    void partialIndexViolation_neverSurfacesAs500() throws Exception {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
        // Both requests see no live payment, so both reach the insert and the index decides.
        doReturn(false).when(payments).existsLiveFor(any());
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<CompletableFuture<ChargePaymentResult>>();
        for (int i = 0; i < 2; i++) {
            var key = UUID.randomUUID();
            outcomes.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                await(start);
                                return chargePayment.charge(command(r, key));
                            },
                            threads));
        }
        start.countDown();

        List<ChargePaymentResult> results = new ArrayList<>();
        for (var outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }

        assertThat(results)
                .hasSize(2)
                .anyMatch(ChargePaymentResult.Processed.class::isInstance)
                .anyMatch(ChargePaymentResult.PaymentInProgress.class::isInstance);
        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("PENDING");
        verify(gateway, times(1)).create(any(), any(), any(), any());
        assertThat(fixtures.chargeKeys(r.student())).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "a registration that is no longer RESERVED is a recorded rejection and calls no gateway")
    void expiredHold_isNotReserved_noGateway() {
        var r = aReservedStudent();
        fixtures.expireTheHold(r.registration().value());
        var key = UUID.randomUUID();

        var first = chargePayment.charge(command(r, key));
        var replay = chargePayment.charge(command(r, key));

        assertThat(first).isEqualTo(new ChargePaymentResult.RegistrationNotReserved());
        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.paymentStatuses(r.registration().value())).isEmpty();
        verify(gateway, never()).create(any(), any(), any(), any());
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
