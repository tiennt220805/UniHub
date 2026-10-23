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
import com.stripe.net.Webhook;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.in.ReceivePaymentWebhookUseCase;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.payment.application.port.in.RequestPaymentReconciliationsUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCancelRefusedException;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * M-23, INV-04 (payment.md §6, §7; checklist D16, AC16.4, G2/AC1.12): a payment stuck {@code
 * PENDING} is found by the sweep and resolved from the gateway's own record, with no webhook at
 * all, and what it does to the payment, its idempotency key and the outbox follows the gateway's
 * status row by row. Racing the webhook for the same payment always converges on one status. Not
 * {@code @Transactional}: the property is about what is committed. The sweep is called directly;
 * its scheduler is off in every context (IntegrationTestSupport).
 */
@SpringBootTest
class PaymentReconciliationIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "webhook-secret-for-tests";
    private static final Duration STALE = Duration.ofMinutes(20);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.payment.stripe.webhook.secret", () -> SECRET);
    }

    @Autowired private ChargePaymentUseCase chargePayment;
    @Autowired private ReserveSeatUseCase reserveSeat;
    @Autowired private ReconcilePaymentUseCase reconcile;
    @Autowired private RequestPaymentReconciliationsUseCase sweep;
    @Autowired private ReceivePaymentWebhookUseCase webhook;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @MockBean private PaymentGateway gateway;
    @SpyBean private IdempotencyStore idempotency;

    private final ExecutorService threads = Executors.newFixedThreadPool(4);
    private RegistrationFixtures fixtures;

    /** A payment the gateway never settled, and the key its charge was made under. */
    private record Stuck(
            UserId student, UUID registration, UUID payment, String gatewayId, UUID key) {}

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway, idempotency);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
    }

    @AfterEach
    void cleanUp() {
        threads.shutdownNow();
        reset(gateway, idempotency);
        // The requests the sweep wrote name a payment and nothing else, and are this class's alone.
        jdbc.update(
                "DELETE FROM event_publications WHERE routing_key ="
                        + " 'unihub.payment.reconciliation.requested'");
        fixtures.cleanUp();
    }

    /** Not an IllegalStateException: a repository proxy would translate that one. */
    private static final class CrashBeforeTx2 extends RuntimeException {
        CrashBeforeTx2() {
            super("crash between the transactions");
        }
    }

    private Stuck aStuckPayment(Duration age) {
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var workshopId = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var actor = new Actor.UserActor(student, Set.of());
        var reserved =
                (ReserveSeatResult.Reserved)
                        reserveSeat.reserve(
                                new ReserveSeatCommand(
                                        actor,
                                        workshopId,
                                        UUID.randomUUID().toString(),
                                        RequestFingerprint.of(
                                                JSON, Map.of("workshopId", workshopId.value()))));
        var key = UUID.randomUUID();
        var processed =
                (ChargePaymentResult.Processed)
                        chargePayment.charge(charge(student, reserved.id(), key));
        assertThat(processed.status()).isEqualTo(ChargePaymentResult.Status.PENDING);
        fixtures.backdatePayment(processed.paymentId(), age);
        return new Stuck(
                student,
                reserved.id().value(),
                processed.paymentId(),
                processed.gatewayTransactionId(),
                key);
    }

    private static ChargePaymentCommand charge(UserId student, RegistrationId reg, UUID key) {
        return new ChargePaymentCommand(
                new Actor.UserActor(student, Set.of()),
                reg,
                "pm_card_visa",
                key.toString(),
                "fingerprint");
    }

    /** The same charge, again: what the client does when it lost the response. */
    private ChargePaymentResult retryCharge(Stuck stuck) {
        return chargePayment.charge(
                charge(stuck.student(), RegistrationId.of(stuck.registration()), stuck.key()));
    }

    /** A charge whose second transaction cannot run, as a crash between the two would leave it. */
    private Stuck aCrashedCharge() {
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var workshopId = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var actor = new Actor.UserActor(student, Set.of());
        var reserved =
                (ReserveSeatResult.Reserved)
                        reserveSeat.reserve(
                                new ReserveSeatCommand(
                                        actor,
                                        workshopId,
                                        UUID.randomUUID().toString(),
                                        RequestFingerprint.of(
                                                JSON, Map.of("workshopId", workshopId.value()))));
        var key = UUID.randomUUID();
        // Stubbed in a transaction: the port is MANDATORY and the spy sits behind that proxy.
        tx.executeWithoutResult(
                s ->
                        doThrow(new CrashBeforeTx2())
                                .when(idempotency)
                                .complete(any(), any(), any(), any(), any()));
        try {
            assertThatThrownBy(() -> chargePayment.charge(charge(student, reserved.id(), key)))
                    .isInstanceOf(CrashBeforeTx2.class);
        } finally {
            reset(idempotency);
        }
        var paymentId =
                jdbc.queryForObject(
                        "SELECT id FROM payments WHERE registration_id = ?",
                        UUID.class,
                        reserved.id().value());
        fixtures.backdatePayment(paymentId, STALE);
        var gatewayId =
                jdbc.queryForObject(
                        "SELECT gateway_transaction_id FROM payments WHERE id = ?",
                        String.class,
                        paymentId);
        return new Stuck(student, reserved.id().value(), paymentId, gatewayId, key);
    }

    private void reconcile(Stuck stuck) {
        reconcile.reconcile(new ReconcilePaymentCommand(PaymentId.of(stuck.payment())));
    }

    private String status(UUID payment) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, payment);
    }

    private long version(UUID payment) {
        return jdbc.queryForObject(
                "SELECT version FROM payments WHERE id = ?", Long.class, payment);
    }

    // ---------- the sweep ----------

    @Test
    @DisplayName("the sweep requests exactly the payments PENDING for more than 15 minutes")
    void sweep_requestsOnlyTheStalePayments() {
        var stale = aStuckPayment(Duration.ofMinutes(16));
        var fresh = aStuckPayment(Duration.ofMinutes(14));
        var resolved = aStuckPayment(Duration.ofMinutes(30));
        jdbc.update(
                "UPDATE payments SET status = 'FAILED', resolved_at = now() WHERE id = ?",
                resolved.payment());

        int requested = sweep.requestReconciliations();

        assertThat(requested).isGreaterThanOrEqualTo(1);
        assertThat(fixtures.reconciliationRequests(stale.payment())).isEqualTo(1);
        assertThat(fixtures.reconciliationRequests(fresh.payment())).isZero();
        assertThat(fixtures.reconciliationRequests(resolved.payment())).isZero();
        verify(gateway, never()).retrieve(any());
    }

    // ---------- M-23: resolved without a webhook ----------

    @Test
    @DisplayName("M-23: a stuck payment the gateway settled is SUCCEEDED with no webhook, once")
    void stuck_succeededAtTheGateway_isResolvedWithoutAWebhook() {
        var stuck = aStuckPayment(STALE);
        when(gateway.retrieve(stuck.gatewayId())).thenReturn(GatewayPaymentState.SUCCEEDED);

        reconcile(stuck);
        reconcile(stuck); // a duplicated request for the same payment

        assertThat(status(stuck.payment())).isEqualTo("SUCCEEDED");
        assertThat(fixtures.paymentOutboxRows(stuck.registration()))
                .as("one charge.succeeded, however many requests")
                .isEqualTo(1);
        assertThat(fixtures.chargeKeyStatus(stuck.student())).isEqualTo("COMPLETED");
        verify(gateway, times(1)).retrieve(stuck.gatewayId());
    }

    @Test
    @DisplayName("a refused card is FAILED; the initiated key keeps its snapshot, not a new charge")
    void stuck_declined_isFailed_andTheKeyKeepsItsSnapshot() {
        var stuck = aStuckPayment(STALE);
        when(gateway.retrieve(stuck.gatewayId())).thenReturn(GatewayPaymentState.DECLINED);

        reconcile(stuck);

        assertThat(status(stuck.payment())).isEqualTo("FAILED");
        assertThat(fixtures.paymentOutboxRows(stuck.registration())).isZero();
        assertThat(fixtures.chargeKeyStatus(stuck.student())).isEqualTo("COMPLETED");
        var replay = (ChargePaymentResult.Processed) retryCharge(stuck);
        assertThat(replay.status())
                .as("a snapshot of the moment it completed; getPayment is the truth (D10)")
                .isEqualTo(ChargePaymentResult.Status.PENDING);
        verify(gateway, times(1)).create(any(), any(), any(), any());
        verify(gateway, times(1)).confirm(any(), any());
    }

    @Test
    @DisplayName(
            "a crashed charge the gateway refused is FAILED with its key completed: it replays")
    void crashedCharge_declined_keyIsCompleted_andTheRetryReplaysFailed() {
        var crashed = aCrashedCharge();
        when(gateway.retrieve(crashed.gatewayId())).thenReturn(GatewayPaymentState.DECLINED);

        reconcile(crashed);

        assertThat(status(crashed.payment())).isEqualTo("FAILED");
        assertThat(fixtures.chargeKeyStatus(crashed.student())).isEqualTo("COMPLETED");
        var replay = (ChargePaymentResult.Processed) retryCharge(crashed);
        assertThat(replay.status()).isEqualTo(ChargePaymentResult.Status.FAILED);
        verify(gateway, times(1)).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("never confirmed: cancelled at the gateway and CANCELLED, no event")
    void stuck_neverConfirmed_isCancelled() {
        var stuck = aStuckPayment(STALE);
        when(gateway.retrieve(stuck.gatewayId())).thenReturn(GatewayPaymentState.NEVER_CONFIRMED);

        reconcile(stuck);

        verify(gateway).cancel(PaymentId.of(stuck.payment()), stuck.gatewayId());
        assertThat(status(stuck.payment())).isEqualTo("CANCELLED");
        assertThat(fixtures.paymentOutboxRows(stuck.registration())).isZero();
        assertThat(fixtures.chargeKeyStatus(stuck.student()))
                .as("a key already completed as 'initiated' is not an open key: untouched")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a gateway id that was never stored: CANCELLED, key deleted, no gateway call")
    void crashedBeforeTheId_isCancelledWithoutAskingTheGateway() {
        var crashed = aCrashedCharge();
        jdbc.update(
                "UPDATE payments SET gateway_transaction_id = NULL WHERE id = ?",
                crashed.payment());

        reconcile(crashed);

        assertThat(status(crashed.payment())).isEqualTo("CANCELLED");
        assertThat(fixtures.chargeKeyStatus(crashed.student())).isNull();
        verify(gateway, never()).retrieve(any());
        verify(gateway, never()).cancel(any(), any());
        retryCharge(crashed);
        verify(gateway, times(2)).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("still processing: stays PENDING, untouched, key untouched, nothing cancelled")
    void stuck_processing_staysPending() {
        var stuck = aStuckPayment(STALE);
        when(gateway.retrieve(stuck.gatewayId())).thenReturn(GatewayPaymentState.PROCESSING);
        var versionBefore = version(stuck.payment());

        reconcile(stuck);

        assertThat(status(stuck.payment())).isEqualTo("PENDING");
        assertThat(version(stuck.payment())).isEqualTo(versionBefore);
        assertThat(fixtures.chargeKeyStatus(stuck.student())).isEqualTo("COMPLETED");
        verify(gateway, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("the cancel is refused because it succeeded meanwhile: SUCCEEDED, key completed")
    void stuck_cancelRefused_resolvesFromTheRealStatus() {
        var stuck = aStuckPayment(STALE);
        when(gateway.retrieve(stuck.gatewayId()))
                .thenReturn(GatewayPaymentState.NEVER_CONFIRMED, GatewayPaymentState.SUCCEEDED);
        doThrow(new GatewayCancelRefusedException("it succeeded", null))
                .when(gateway)
                .cancel(any(), any());

        reconcile(stuck);

        assertThat(status(stuck.payment())).isEqualTo("SUCCEEDED");
        assertThat(fixtures.paymentOutboxRows(stuck.registration())).isEqualTo(1);
        assertThat(fixtures.chargeKeyStatus(stuck.student())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a gateway that cannot be asked leaves the payment as it was, to be retried")
    void stuck_gatewayDown_changesNothing() {
        var stuck = aStuckPayment(STALE);
        when(gateway.retrieve(stuck.gatewayId()))
                .thenThrow(new GatewayCallFailedException("down", null));
        var versionBefore = version(stuck.payment());

        assertThatThrownBy(() -> reconcile(stuck)).isInstanceOf(GatewayCallFailedException.class);

        assertThat(status(stuck.payment())).isEqualTo("PENDING");
        assertThat(version(stuck.payment())).isEqualTo(versionBefore);
    }

    // ---------- G2 / AC1.12 ----------

    @Test
    @DisplayName("G2: a charge that crashed between its transactions is closed by reconciliation")
    void crashedCharge_keyIsCompletedByReconciliation_andTheRetryReplays() {
        var crashed = aCrashedCharge();
        assertThat(fixtures.chargeKeyStatus(crashed.student())).isEqualTo("IN_PROGRESS");
        assertThat(status(crashed.payment())).isEqualTo("PENDING");
        when(gateway.retrieve(crashed.gatewayId())).thenReturn(GatewayPaymentState.SUCCEEDED);

        reconcile(crashed);

        assertThat(status(crashed.payment())).isEqualTo("SUCCEEDED");
        assertThat(fixtures.chargeKeyStatus(crashed.student())).isEqualTo("COMPLETED");
        var replay = (ChargePaymentResult.Processed) retryCharge(crashed);
        assertThat(replay.status())
                .as("the same-key retry replays, instead of getting 409 for ever")
                .isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        verify(gateway, times(1)).create(any(), any(), any(), any());
        verify(gateway, times(1)).confirm(any(), any());
    }

    @Test
    @DisplayName("G2: a crashed charge proved never confirmed has its key deleted, and runs again")
    void crashedCharge_neverConfirmed_keyIsDeleted_andTheRetryRunsForReal() {
        var crashed = aCrashedCharge();
        when(gateway.retrieve(crashed.gatewayId())).thenReturn(GatewayPaymentState.NEVER_CONFIRMED);

        reconcile(crashed);

        assertThat(status(crashed.payment())).isEqualTo("CANCELLED");
        assertThat(fixtures.chargeKeyStatus(crashed.student())).isNull();
        retryCharge(crashed);
        verify(gateway, times(2)).create(any(), any(), any(), any());
    }

    // ---------- the race with the webhook ----------

    private static String eventBody(String type, String gatewayId) {
        return "{\"id\":\"evt_"
                + UUID.randomUUID()
                + "\",\"type\":\""
                + type
                + "\",\"data\":{\"object\":{\"id\":\""
                + gatewayId
                + "\",\"object\":\"payment_intent\"}}}";
    }

    private static String sign(String body) {
        try {
            return Webhook.Signature.generateSignatureHeader(
                    body, SECRET, Instant.now().getEpochSecond());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName(
            "M-23: the webhook and reconciliation racing for one payment leave one status, one event")
    void raceWithTheWebhook_convergesOnOneStatusAndOneEvent() throws Exception {
        when(gateway.retrieve(any())).thenReturn(GatewayPaymentState.SUCCEEDED);
        var failures = new ArrayList<Throwable>();
        for (int round = 0; round < 8; round++) {
            var stuck = aStuckPayment(STALE);
            var body = eventBody("payment_intent.succeeded", stuck.gatewayId());
            var signature = sign(body);
            var start = new CountDownLatch(1);
            var viaWebhook =
                    CompletableFuture.runAsync(
                            () -> {
                                await(start);
                                webhook.receive(body, signature);
                            },
                            threads);
            var viaSweep =
                    CompletableFuture.runAsync(
                            () -> {
                                await(start);
                                reconcile(stuck);
                            },
                            threads);
            start.countDown();
            try {
                CompletableFuture.allOf(viaWebhook, viaSweep).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                failures.add(e);
            }

            assertThat(status(stuck.payment())).isEqualTo("SUCCEEDED");
            assertThat(fixtures.paymentOutboxRows(stuck.registration()))
                    .as("round %d: one charge.succeeded, whoever won", round)
                    .isEqualTo(1);
            assertThat(fixtures.chargeKeyStatus(stuck.student())).isEqualTo("COMPLETED");
        }
        assertThat(failures).as("neither side may fail on losing the race").isEmpty();
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
