package com.unihub.payment.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.unihub.payment.application.port.out.GatewayAdmission;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayOutcomeUnknownException;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentMethodRejectedException;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.payment.domain.exception.PayableRegistrationNotFoundException;
import com.unihub.payment.domain.exception.PaymentGatewayUnavailableException;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
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
class ChargePaymentIdempotencyIT extends IntegrationTestSupport {
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
    @SpyBean private ChargePaymentSteps steps;
    @MockBean private GatewayAdmission admission;

    private final ExecutorService threads = Executors.newFixedThreadPool(4);
    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway, idempotency, payments, steps, admission);
        when(admission.tryAcquire()).thenReturn(true);
        // A fresh id per call, as the gateway gives: payments.gateway_transaction_id is UNIQUE.
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        threads.shutdownNow();
        reset(gateway, idempotency, payments, steps, admission);
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

    // ---------- one key, one effect ----------

    @Test
    @DisplayName("the same key reaches the gateway once and replays the original Result")
    void sameKey_gatewayCalledOnce() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());

        var first = chargePayment.charge(command(r, key));
        var replay = chargePayment.charge(command(r, key));

        assertThat(replay).isEqualTo(first);
        verify(gateway, times(1)).create(any(), any(), any(), any());
        verify(gateway, times(1)).confirm(any(), any());
        assertThat(fixtures.paymentStatuses(r.registration().value())).hasSize(1);
        assertThat(fixtures.paymentOutboxRows(r.registration().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with another body is refused and calls no gateway")
    void sameKeyDifferentBody_isReused() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        chargePayment.charge(command(r, key));
        var otherBody =
                new ChargePaymentCommand(
                        new Actor.UserActor(r.student(), Set.of()),
                        r.registration(),
                        "pm_card_mastercard",
                        key.toString(),
                        "another-fingerprint");

        assertThatThrownBy(() -> chargePayment.charge(otherBody))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verify(gateway, times(1)).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the same key from another student is an independent operation")
    void sameKeyOtherActor_isIndependent() {
        var a = aReservedStudent();
        var b = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());

        var first = processed(chargePayment.charge(command(a, key)));
        var second = processed(chargePayment.charge(command(b, key)));

        assertThat(second.paymentId()).isNotEqualTo(first.paymentId());
        verify(gateway, times(2)).confirm(any(), any());
    }

    @Test
    @DisplayName(
            "while the first request is mid-flight, the same key is 'in use' and the gateway is called once")
    void concurrentSameKey_inUse_gatewayCalledOnce() throws Exception {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        var insideConfirm = new CountDownLatch(1);
        var releaseConfirm = new CountDownLatch(1);
        when(gateway.confirm(any(), any()))
                .thenAnswer(
                        call -> {
                            insideConfirm.countDown();
                            await(releaseConfirm);
                            return new ConfirmOutcome.Succeeded();
                        });
        var first =
                CompletableFuture.supplyAsync(() -> chargePayment.charge(command(r, key)), threads);
        assertThat(insideConfirm.await(10, TimeUnit.SECONDS)).isTrue();

        // tx1 has committed and the confirm is in flight: the key is IN_PROGRESS for everyone else
        assertThatThrownBy(() -> chargePayment.charge(command(r, key)))
                .isInstanceOf(IdempotencyKeyInUseException.class);
        releaseConfirm.countDown();

        assertThat(processed(first.get(30, TimeUnit.SECONDS)).status())
                .isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        verify(gateway, times(1)).confirm(any(), any());
        assertThat(chargePayment.charge(command(r, key))).isEqualTo(first.get());
    }

    // ---------- G1: the breaker ----------

    @Test
    @DisplayName("a refused admission writes nothing: no payment, no key, no gateway call")
    void circuitOpenBeforeBegin_writesNothing() {
        var r = aReservedStudent();
        when(admission.tryAcquire()).thenReturn(false);

        assertThatThrownBy(() -> chargePayment.charge(command(r, UUID.randomUUID())))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        assertThat(fixtures.paymentStatuses(r.registration().value())).isEmpty();
        assertThat(fixtures.chargeKeys(r.student())).isZero();
        verify(gateway, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "a confirm refused after tx1 cancels the payment and drops the key; the retry calls the gateway for real, once")
    void breakerRejectsAfterBegin_cancelsAndDropsKey_retryExecutes() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any()))
                .thenThrow(new GatewayNotReachedException("breaker open", null))
                .thenReturn(new ConfirmOutcome.Succeeded());

        assertThatThrownBy(() -> chargePayment.charge(command(r, key)))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("CANCELLED");
        assertThat(fixtures.chargeKeys(r.student())).isZero();

        var retry = processed(chargePayment.charge(command(r, key)));

        assertThat(retry.status()).isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        verify(gateway, times(2)).confirm(any(), any());
        assertThat(fixtures.chargeKeyStatus(r.student())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a confirm that never left UniHub drops the key too (AC16.2)")
    void confirmNeverLeft_cancelsAndDropsKey_retryExecutes() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any()))
                .thenThrow(new GatewayNotReachedException("connection refused", null));

        assertThatThrownBy(() -> chargePayment.charge(command(r, key)))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("CANCELLED");
        assertThat(fixtures.chargeKeys(r.student())).isZero();
        assertThat(fixtures.paymentOutboxRows(r.registration().value())).isZero();
    }

    // ---------- D16: what a timeout means ----------

    @Test
    @DisplayName(
            "a timeout after the confirm was sent is 201 PENDING, the key is completed, and a retry never confirms again")
    void timeoutAfterConfirmSent_pending_keyCompleted_retryReplays() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any()))
                .thenThrow(new GatewayOutcomeUnknownException("read timeout", null));

        var first = processed(chargePayment.charge(command(r, key)));
        var retry = chargePayment.charge(command(r, key));

        assertThat(first.status()).isEqualTo(ChargePaymentResult.Status.PENDING);
        assertThat(first.gatewayTransactionId())
                .as("the id was stored before the confirm")
                .isNotNull();
        assertThat(retry).isEqualTo(first);
        assertThat(fixtures.chargeKeyStatus(r.student())).isEqualTo("COMPLETED");
        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("PENDING");
        verify(gateway, times(1)).confirm(any(), any());
    }

    @Test
    @DisplayName(
            "any failure of create, a read timeout included, charges nothing: payment cancelled, key dropped")
    void createReadTimeout_isNotCharged_cancelsAndDropsKey() {
        var r = aReservedStudent();
        when(gateway.create(any(), any(), any(), any()))
                .thenThrow(new GatewayCallFailedException("read timeout", null));

        assertThatThrownBy(() -> chargePayment.charge(command(r, UUID.randomUUID())))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("CANCELLED");
        assertThat(fixtures.chargeKeys(r.student())).isZero();
        verify(gateway, never()).confirm(any(), any());
    }

    @Test
    @DisplayName("failing to store the gateway id charges nothing: the confirm is never sent")
    void storeIdFailure_cancelsAndDropsKey() {
        var r = aReservedStudent();
        tx.executeWithoutResult(s -> doThrow(new DatabaseDown()).when(steps).attach(any(), any()));

        assertThatThrownBy(() -> chargePayment.charge(command(r, UUID.randomUUID())))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("CANCELLED");
        assertThat(fixtures.chargeKeys(r.student())).isZero();
        verify(gateway, never()).confirm(any(), any());
    }

    @Test
    @DisplayName(
            "a refused payment method token is FAILED and recorded: the same key replays it, no 503")
    void tokenRefused_isFailed_andReplays() {
        var r = aReservedStudent();
        var key = UUID.randomUUID();
        when(gateway.create(any(), any(), any(), any()))
                .thenThrow(new PaymentMethodRejectedException("no such pm", null));

        var first = processed(chargePayment.charge(command(r, key)));
        var replay = chargePayment.charge(command(r, key));

        assertThat(first.status()).isEqualTo(ChargePaymentResult.Status.FAILED);
        assertThat(first.gatewayTransactionId()).isNull();
        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.chargeKeyStatus(r.student())).isEqualTo("COMPLETED");
        verify(gateway, never()).confirm(any(), any());
    }

    // ---------- nothing recorded for 403 and 404 ----------

    @Test
    @DisplayName("another student's registration is refused and records nothing")
    void otherUsersRegistration_isRefused_recordsNothing() {
        var owner = aReservedStudent();
        var stranger = fixtures.aStudent();
        var asStranger =
                new ChargePaymentCommand(
                        new Actor.UserActor(stranger, Set.of()),
                        owner.registration(),
                        "pm_card_visa",
                        UUID.randomUUID().toString(),
                        "f");

        assertThatThrownBy(() -> chargePayment.charge(asStranger))
                .isInstanceOf(NotRegistrationOwnerException.class);

        assertThat(fixtures.chargeKeys(stranger)).isZero();
        assertThat(fixtures.paymentStatuses(owner.registration().value())).isEmpty();
        verify(gateway, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown registration is a 404 and records nothing")
    void unknownRegistration_recordsNothing() {
        var r = aReservedStudent();
        var unknown =
                new ChargePaymentCommand(
                        new Actor.UserActor(r.student(), Set.of()),
                        RegistrationId.newId(),
                        "pm_card_visa",
                        UUID.randomUUID().toString(),
                        "f");

        assertThatThrownBy(() -> chargePayment.charge(unknown))
                .isInstanceOf(PayableRegistrationNotFoundException.class);

        assertThat(fixtures.chargeKeys(r.student())).isZero();
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
