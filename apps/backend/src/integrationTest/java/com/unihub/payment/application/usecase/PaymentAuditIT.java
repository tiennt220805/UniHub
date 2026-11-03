package com.unihub.payment.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.payment.application.port.in.RequireRefundUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayOutcomeUnknownException;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ConfirmRegistrationUseCase;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.RecordedAudit;
import com.unihub.shared.infrastructure.out.messaging.RabbitAuditTrail;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every money-moving change leaves exactly one audit entry, and none of it can hurt the change
 * (Phase 2 checklist D6, AC6.1-AC6.3; payment.md §8; INV-14). The real {@code RabbitAuditTrail} on
 * real Postgres, with {@link RecordedAudit} standing in for the broker to read what it published
 * (Phase 6 checklist 2.5; that an entry reaches {@code audit_events} is {@code AuditTrailIT}).
 *
 * <p>Three properties, each on the committed state: an entry exists exactly when its transaction
 * committed (a rolled-back change leaves none), the actor is the student for their own charge and a
 * named process for anything the system does by itself, and a broken audit pipeline neither fails
 * nor rolls back the payment, and is counted.
 */
@SpringBootTest
@Import(RecordedAudit.Config.class)
class PaymentAuditIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private ChargePaymentUseCase chargePayment;
    @Autowired private ReconcilePaymentUseCase reconcile;
    @Autowired private RequireRefundUseCase requireRefund;
    @Autowired private ConfirmRegistrationUseCase confirmRegistration;
    @Autowired private ReserveSeatUseCase reserveSeat;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @Autowired private RecordedAudit audit;
    @Autowired private AuditTrail auditTrail;
    @Autowired private MeterRegistry meters;

    @MockBean private PaymentGateway gateway;
    @SpyBean private IdempotencyStore idempotency;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway, idempotency);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        audit.failWith(null);
        forgetWhatWasAudited();
        // The seat reservation below audits too; the tests look at what follows.
    }

    @AfterEach
    void cleanUp() {
        audit.failWith(null);
        reset(gateway, idempotency);
        fixtures.cleanUp();
    }

    /** Not an IllegalArgument/IllegalState: a repository proxy would translate those. */
    private static final class DatabaseDown extends RuntimeException {
        DatabaseDown() {
            super("db down");
        }
    }

    private record Reserved(UserId student, RegistrationId registration) {}

    private Reserved aReservedStudent() {
        var workshop = aPaidWorkshop().withCapacity(5).build();
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
        forgetWhatWasAudited(); // the reservation's own REGISTRATION_CREATED is not under test
        return new Reserved(student, reserved.id());
    }

    private ChargePaymentCommand command(Reserved r) {
        return new ChargePaymentCommand(
                new Actor.UserActor(r.student(), Set.of()),
                r.registration(),
                "pm_card_visa",
                UUID.randomUUID().toString(),
                RequestFingerprint.of(
                        JSON,
                        Map.of(
                                "registrationId",
                                r.registration().value(),
                                "paymentMethodToken",
                                "pm_card_visa")));
    }

    private static PaymentId paymentIdOf(ChargePaymentResult result) {
        return PaymentId.of(((ChargePaymentResult.Processed) result).paymentId());
    }

    /** Waits for the trail to publish what it was handed, then drops it. */
    private void forgetWhatWasAudited() {
        audit.settle(auditTrail);
        audit.clear();
    }

    /** The audit entries published so far, once the trail has published everything handed to it. */
    private List<JsonNode> written() {
        return audit.settle(auditTrail);
    }

    private List<String> actions() {
        return written().stream().map(l -> l.get("action").asText()).toList();
    }

    private double auditFailures() {
        var counter =
                meters.find(RabbitAuditTrail.FAILURE_COUNTER).tag("reason", "error").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("a charge that succeeds leaves INITIATED then SUCCEEDED, both under the student")
    void charge_success_isAuditedUnderTheStudent() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());

        var paymentId = paymentIdOf(chargePayment.charge(command(r)));

        assertThat(actions()).containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCEEDED");
        for (var line : written()) {
            assertThat(line.get("actorType").asText()).isEqualTo("USER");
            assertThat(line.get("actorUserId").asText()).isEqualTo(r.student().value().toString());
            assertThat(line.get("targetType").asText()).isEqualTo("PAYMENT");
            assertThat(line.get("targetId").asText()).isEqualTo(paymentId.value().toString());
            assertThat(line.get("metadata").get("registrationId").asText())
                    .isEqualTo(r.registration().value().toString());
        }
    }

    @Test
    @DisplayName("a declined card leaves INITIATED then FAILED")
    void charge_declined_isAudited() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Declined("generic"));

        chargePayment.charge(command(r));

        assertThat(actions()).containsExactly("PAYMENT_INITIATED", "PAYMENT_FAILED");
    }

    @Test
    @DisplayName(
            "a charge whose second transaction rolls back leaves the INITIATED line and no SUCCEEDED")
    void charge_tx2Rollback_leavesNoLineForTheRolledBackChange() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        tx.executeWithoutResult(
                s ->
                        doThrow(new DatabaseDown())
                                .when(idempotency)
                                .complete(any(), any(), any(), any(), any()));

        assertThatThrownBy(() -> chargePayment.charge(command(r))).isInstanceOf(DatabaseDown.class);

        assertThat(fixtures.paymentStatuses(r.registration().value())).containsExactly("PENDING");
        assertThat(actions())
                .as("tx1 committed and is audited; tx2 rolled back and must not be")
                .containsExactly("PAYMENT_INITIATED");
    }

    @Test
    @DisplayName(
            "a charge the system has to undo because the gateway is unreachable is cancelled by a process")
    void charge_gatewayUnreachable_isCancelledByTheSystem() {
        var r = aReservedStudent();
        when(gateway.create(any(), any(), any(), any()))
                .thenThrow(new GatewayCallFailedException("down", null));

        assertThatThrownBy(() -> chargePayment.charge(command(r)))
                .hasMessageContaining("temporarily unavailable");

        assertThat(actions()).containsExactly("PAYMENT_INITIATED", "PAYMENT_CANCELLED");
        var cancelled = written().get(1);
        assertThat(cancelled.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(cancelled.has("actorUserId")).isFalse();
        assertThat(cancelled.get("metadata").get("process").asText())
                .isEqualTo("payment-charge-compensation");
    }

    @Test
    @DisplayName(
            "reconciliation settling a stuck payment is recorded under its own process, not a user")
    void reconciliation_isAuditedAsAProcess() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any()))
                .thenThrow(new GatewayOutcomeUnknownException("timed out", null));
        var paymentId = paymentIdOf(chargePayment.charge(command(r)));
        forgetWhatWasAudited();
        when(gateway.retrieve(any())).thenReturn(GatewayPaymentState.SUCCEEDED);

        reconcile.reconcile(new ReconcilePaymentCommand(paymentId));

        assertThat(actions()).containsExactly("PAYMENT_SUCCEEDED");
        assertThat(written().getFirst().get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(written().getFirst().get("metadata").get("process").asText())
                .isEqualTo("payment-reconciliation");
    }

    @Test
    @DisplayName(
            "the refund step and the registration confirmation are each recorded once, redelivery or not")
    void consumers_areAuditedOnce() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        var paymentId = paymentIdOf(chargePayment.charge(command(r)));
        forgetWhatWasAudited();

        confirmRegistration.confirm(new ConfirmRegistrationCommand(paymentId, r.registration()));
        confirmRegistration.confirm(new ConfirmRegistrationCommand(paymentId, r.registration()));
        requireRefund.requireRefund(new RequireRefundCommand(paymentId));
        requireRefund.requireRefund(new RequireRefundCommand(paymentId));

        assertThat(actions())
                .as("a redelivery is a no-op and so leaves no second line")
                .containsExactly("REGISTRATION_CONFIRMED", "PAYMENT_REFUND_REQUIRED");
        assertThat(written().get(0).get("metadata").get("process").asText())
                .isEqualTo("payment-confirmation");
        assertThat(written().get(1).get("metadata").get("process").asText())
                .isEqualTo("payment-refund");
    }

    @Test
    @DisplayName("a broken audit pipeline neither fails nor rolls back the charge; it is counted")
    void brokenAuditPipeline_neverHurtsThePayment() {
        var r = aReservedStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        var before = auditFailures();
        audit.failWith(new IllegalStateException("broker gone"));

        var result = chargePayment.charge(command(r));

        assertThat(((ChargePaymentResult.Processed) result).status())
                .isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        assertThat(fixtures.paymentStatuses(r.registration().value()))
                .as("the payment is committed whatever happened to its audit entries")
                .containsExactly("SUCCEEDED");
        assertThat(fixtures.paymentOutboxRows(r.registration().value())).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(auditFailures() - before)
                                        .as(
                                                "INITIATED and SUCCEEDED both failed to be"
                                                        + " published, and both were counted")
                                        .isEqualTo(2));
    }
}
