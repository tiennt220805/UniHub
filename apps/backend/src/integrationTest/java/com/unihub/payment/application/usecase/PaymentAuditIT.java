package com.unihub.payment.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.Actor;
import com.unihub.shared.security.TestJwts;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every money-moving change leaves exactly one audit line, and none of it can hurt the change
 * (Phase 2 checklist D6, AC6.1-AC6.3; payment.md §8; INV-14). The real {@code LoggingAuditTrail} on
 * real Postgres, with a list appender on the {@code unihub.audit} logger to read what it wrote.
 *
 * <p>Three properties, each on the committed state: an entry exists exactly when its transaction
 * committed (a rolled-back change leaves none), the actor is the student for their own charge and a
 * named process for anything the system does by itself, and a broken audit pipeline neither fails
 * nor rolls back the payment.
 */
@SpringBootTest
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

    @MockBean private PaymentGateway gateway;
    @SpyBean private IdempotencyStore idempotency;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("unihub.audit");
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway, idempotency);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        Metrics.addRegistry(meters);
        lines.start();
        auditLogger.addAppender(lines);
        auditLogger.setLevel(Level.INFO);
        // The seat reservation below writes its own line; the tests look at what follows.
    }

    @AfterEach
    void cleanUp() {
        auditLogger.detachAppender(lines);
        Metrics.removeRegistry(meters);
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
        lines.list.clear(); // the reservation's own REGISTRATION_CREATED line is not under test
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

    /** The audit lines written so far, parsed. */
    private List<JsonNode> written() {
        return lines.list.stream()
                .map(
                        event -> {
                            try {
                                return JSON.readTree(event.getFormattedMessage());
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .toList();
    }

    private List<String> actions() {
        return written().stream().map(l -> l.get("action").asText()).toList();
    }

    private double auditFailures() {
        var counter = meters.find("unihub.audit.publish.failures").counter();
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
        lines.list.clear();
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
        lines.list.clear();

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
        auditLogger.detachAppender(lines);
        var broken =
                new ListAppender<ILoggingEvent>() {
                    {
                        start();
                    }

                    @Override
                    protected void append(ILoggingEvent event) {
                        throw new IllegalStateException("disk full");
                    }

                    @Override
                    public synchronized void doAppend(ILoggingEvent event) {
                        throw new IllegalStateException("disk full");
                    }
                };
        auditLogger.addAppender(broken);
        try {
            var result = chargePayment.charge(command(r));

            assertThat(((ChargePaymentResult.Processed) result).status())
                    .isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        } finally {
            auditLogger.detachAppender(broken); // never leave a broken appender for the next test
        }

        assertThat(fixtures.paymentStatuses(r.registration().value()))
                .as("the payment is committed whatever happened to its audit lines")
                .containsExactly("SUCCEEDED");
        assertThat(fixtures.paymentOutboxRows(r.registration().value())).isEqualTo(1);
        assertThat(auditFailures())
                .as("INITIATED and SUCCEEDED both failed to be written, and both were counted")
                .isEqualTo(2);
    }
}
