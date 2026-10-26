package com.unihub.payment.application.usecase;

import static com.unihub.payment.application.usecase.AuditAssertions.assertRecorded;
import static com.unihub.payment.application.usecase.AuditAssertions.assertSystemActor;
import static com.unihub.payment.application.usecase.AuditAssertions.onlyEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.api.event.PaymentSucceededIntegrationEvent;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Decided;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Resolution;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Started;
import com.unihub.payment.domain.exception.NotRegistrationOwnerException;
import com.unihub.payment.domain.exception.PayableRegistrationNotFoundException;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.registration.api.PayableRegistration;
import com.unihub.registration.api.PayableRegistrations;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.api.WorkshopPricing;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ChargePaymentStepsTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");
    private static final IdempotencyScope SCOPE = IdempotencyScope.PAYMENT_CHARGE;
    private static final UserId STUDENT = UserId.newId();
    private static final RegistrationId REGISTRATION = RegistrationId.newId();
    private static final WorkshopId WORKSHOP = WorkshopId.newId();
    private static final String KEY = UUID.randomUUID().toString();
    private static final String FINGERPRINT = "f";
    private static final ChargePaymentCommand COMMAND =
            new ChargePaymentCommand(
                    new Actor.UserActor(STUDENT, Set.of()),
                    REGISTRATION,
                    "pm_card_visa",
                    KEY,
                    FINGERPRINT);

    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final PayableRegistrations registrations = mock(PayableRegistrations.class);
    private final WorkshopPricing pricing = mock(WorkshopPricing.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final ChargePaymentSteps steps =
            new ChargePaymentSteps(
                    idempotency,
                    registrations,
                    pricing,
                    payments,
                    events,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private void registrationIs(UserId owner, boolean reserved, Instant holdEnds) {
        when(registrations.find(REGISTRATION))
                .thenReturn(
                        Optional.of(
                                new PayableRegistration(
                                        REGISTRATION, WORKSHOP, owner, reserved, holdEnds)));
    }

    private void payableWithPrice() {
        registrationIs(STUDENT, true, NOW.plus(Duration.ofMinutes(10)));
        when(pricing.priceOf(WORKSHOP)).thenReturn(Optional.of(new BigDecimal("150000.00")));
    }

    private Payment pendingPayment() {
        return Payment.initiate(
                PaymentId.newId(), REGISTRATION, new BigDecimal("150000.00"), "VND", NOW);
    }

    // ---------- begin (tx1) ----------

    @Test
    @DisplayName("begin: the key is the first write, then the PENDING payment, guarded by its id")
    void begin_keyFirst_thenPayment() {
        payableWithPrice();
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));

        var begun = steps.begin(COMMAND);

        var started = (Started) begun;
        assertThat(started.amount()).isEqualByComparingTo("150000");
        assertThat(started.currency()).isEqualTo("VND");
        var order = inOrder(registrations, payments, idempotency);
        order.verify(registrations).find(REGISTRATION);
        order.verify(idempotency)
                .begin(SCOPE, STUDENT, KEY, FINGERPRINT, started.paymentId().value());
        var saved = ArgumentCaptor.forClass(Payment.class);
        order.verify(payments).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(started.paymentId());
        assertThat(saved.getValue().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getValue().registrationId()).isEqualTo(REGISTRATION);
        assertThat(saved.getValue().initiatedAt()).isEqualTo(NOW);
        verify(idempotency, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("begin: an unknown registration is a 404 and nothing is written or recorded")
    void begin_unknownRegistration_writesNothing() {
        when(registrations.find(REGISTRATION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> steps.begin(COMMAND))
                .isInstanceOf(PayableRegistrationNotFoundException.class);

        verify(idempotency, never()).begin(any(), any(), any(), any(), any());
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("begin: another student's registration is a 403 and nothing is recorded")
    void begin_notTheOwner_writesNothing() {
        registrationIs(UserId.newId(), true, NOW.plus(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> steps.begin(COMMAND))
                .isInstanceOf(NotRegistrationOwnerException.class);

        verify(idempotency, never()).begin(any(), any(), any(), any(), any());
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("begin: a registration that is not RESERVED is a recorded rejection")
    void begin_notReserved_isRecorded() {
        registrationIs(STUDENT, false, null);

        var begun = steps.begin(COMMAND);

        var rejection = new ChargePaymentResult.RegistrationNotReserved();
        assertThat(begun).isEqualTo(new Decided(rejection));
        var order = inOrder(idempotency);
        order.verify(idempotency).begin(SCOPE, STUDENT, KEY, FINGERPRINT, null);
        order.verify(idempotency).complete(SCOPE, STUDENT, KEY, rejection, null);
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("begin: a hold that ends exactly now is over (the boundary is strict)")
    void begin_holdEndingNow_isOver() {
        registrationIs(STUDENT, true, NOW);

        assertThat(steps.begin(COMMAND))
                .isEqualTo(new Decided(new ChargePaymentResult.RegistrationNotReserved()));
    }

    @Test
    @DisplayName("begin: a hold with one microsecond left is still ours to pay for")
    void begin_holdOneMicrosecondLeft_isPayable() {
        registrationIs(STUDENT, true, NOW.plus(Duration.ofNanos(1_000)));
        when(pricing.priceOf(WORKSHOP)).thenReturn(Optional.of(new BigDecimal("1000")));
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(steps.begin(COMMAND)).isInstanceOf(Started.class);
    }

    @Test
    @DisplayName("begin: a registration that already has a live payment is a recorded rejection")
    void begin_livePaymentExists_isRecorded() {
        payableWithPrice();
        when(payments.existsLiveFor(REGISTRATION)).thenReturn(true);

        var begun = steps.begin(COMMAND);

        var rejection = new ChargePaymentResult.PaymentInProgress();
        assertThat(begun).isEqualTo(new Decided(rejection));
        verify(idempotency).complete(SCOPE, STUDENT, KEY, rejection, null);
        verify(payments, never()).save(any());
    }

    @Test
    @DisplayName("recordInProgress: the loser of the index race is recorded under its own key")
    void recordInProgress() {
        var decided = steps.recordInProgress(COMMAND);

        var rejection = new ChargePaymentResult.PaymentInProgress();
        assertThat(decided).isEqualTo(new Decided(rejection));
        var order = inOrder(idempotency);
        order.verify(idempotency).begin(SCOPE, STUDENT, KEY, FINGERPRINT, null);
        order.verify(idempotency).complete(SCOPE, STUDENT, KEY, rejection, null);
    }

    // ---------- attach, resolve (tx2), abandon ----------

    @Test
    @DisplayName("attach: the gateway's id is stored on the payment")
    void attach_storesTheGatewayId() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        steps.attach(payment.id(), "pi_1");

        assertThat(payment.gatewayTransactionId()).isEqualTo("pi_1");
        verify(payments).save(payment);
    }

    @Test
    @DisplayName(
            "resolve SUCCEEDED: saved, outbox row written, then the key completed, in that order")
    void resolve_succeeded() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(payments.save(payment)).thenReturn(payment);

        var result = steps.resolve(COMMAND, payment.id(), Resolution.SUCCEEDED);

        assertThat(((ChargePaymentResult.Processed) result).status())
                .isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
        var order = inOrder(payments, events, idempotency);
        order.verify(payments).save(payment);
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        order.verify(events).publish(message.capture());
        order.verify(idempotency).complete(SCOPE, STUDENT, KEY, result, null);
        assertThat(message.getValue())
                .isEqualTo(
                        new PaymentSucceededIntegrationEvent(
                                payment.id().value(), REGISTRATION.value(), NOW));
    }

    @Test
    @DisplayName("resolve FAILED: the payment fails, no outbox row, the key is completed")
    void resolve_failed() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(payments.save(payment)).thenReturn(payment);

        var result =
                (ChargePaymentResult.Processed)
                        steps.resolve(COMMAND, payment.id(), Resolution.FAILED);

        assertThat(result.status()).isEqualTo(ChargePaymentResult.Status.FAILED);
        assertThat(result.resolvedAt()).isEqualTo(NOW);
        verifyNoInteractions(events);
        verify(idempotency).complete(SCOPE, STUDENT, KEY, result, null);
    }

    @Test
    @DisplayName("resolve INITIATED: the payment stays PENDING, the key is completed")
    void resolve_initiated() {
        var payment = pendingPayment();
        payment.attachGatewayTransactionId("pi_1");
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        var result =
                (ChargePaymentResult.Processed)
                        steps.resolve(COMMAND, payment.id(), Resolution.INITIATED);

        assertThat(result.status()).isEqualTo(ChargePaymentResult.Status.PENDING);
        assertThat(result.gatewayTransactionId()).isEqualTo("pi_1");
        assertThat(result.resolvedAt()).isNull();
        verify(payments, never()).save(any());
        verifyNoInteractions(events);
        verify(idempotency).complete(SCOPE, STUDENT, KEY, result, null);
    }

    @Test
    @DisplayName("resolve: a payment the webhook already resolved is left as it is")
    void resolve_alreadyResolved_isLeftAlone() {
        var payment = pendingPayment();
        payment.fail(NOW);
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        var result =
                (ChargePaymentResult.Processed)
                        steps.resolve(COMMAND, payment.id(), Resolution.SUCCEEDED);

        assertThat(result.status())
                .as("a late success never rewrites a closed payment")
                .isEqualTo(ChargePaymentResult.Status.FAILED);
        verify(payments, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("abandon: the payment is cancelled and the open key deleted")
    void abandon() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        steps.abandon(payment.id());

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        verify(payments).save(payment);
        verify(idempotency).abandonOpenFor(SCOPE, payment.id().value());
        Mockito.verifyNoMoreInteractions(events);
    }

    // ---------- audit (checklist D6) ----------

    @Test
    @DisplayName("audit: begin records PAYMENT_INITIATED once, under the student who pays")
    void audit_begin_recordsInitiationUnderTheStudent() {
        payableWithPrice();
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));

        var started = (Started) steps.begin(COMMAND);

        var entry = onlyEntry(audit);
        assertThat(entry.actor()).isEqualTo(COMMAND.actor());
        assertThat(entry.action()).isEqualTo(AuditActions.PAYMENT_INITIATED);
        assertThat(entry.targetId()).isEqualTo(started.paymentId().value());
        assertThat(entry.occurredAt()).isEqualTo(NOW);
        assertThat(entry.metadata())
                .containsEntry("registrationId", REGISTRATION.value().toString())
                .containsEntry("amount", "150000.00")
                .containsEntry("currency", "VND");
    }

    @Test
    @DisplayName("audit: a rejected charge is not an initiated payment, so nothing is recorded")
    void audit_beginRejected_recordsNothing() {
        registrationIs(STUDENT, false, null);

        steps.begin(COMMAND);

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("audit: a charge the gateway accepts records PAYMENT_SUCCEEDED under the student")
    void audit_resolveSucceeded() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(payments.save(payment)).thenReturn(payment);

        steps.resolve(COMMAND, payment.id(), Resolution.SUCCEEDED);

        var entry = onlyEntry(audit);
        assertThat(entry.actor()).isEqualTo(COMMAND.actor());
        assertRecorded(entry, AuditActions.PAYMENT_SUCCEEDED, payment, NOW);
    }

    @Test
    @DisplayName("audit: a declined charge records PAYMENT_FAILED")
    void audit_resolveFailed() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
        when(payments.save(payment)).thenReturn(payment);

        steps.resolve(COMMAND, payment.id(), Resolution.FAILED);

        assertRecorded(onlyEntry(audit), AuditActions.PAYMENT_FAILED, payment, NOW);
    }

    @Test
    @DisplayName(
            "audit: a payment that stays PENDING (outcome unknown) has not moved: nothing recorded")
    void audit_resolveInitiated_recordsNothing() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        steps.resolve(COMMAND, payment.id(), Resolution.INITIATED);

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("audit: a payment the webhook resolved first is not recorded a second time here")
    void audit_resolveAlreadyResolved_recordsNothing() {
        var payment = pendingPayment();
        payment.fail(NOW);
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        steps.resolve(COMMAND, payment.id(), Resolution.SUCCEEDED);

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("audit: compensating a charge that never reached the gateway is the system's act")
    void audit_abandon_recordsCancellationByTheSystem() {
        var payment = pendingPayment();
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        steps.abandon(payment.id());

        var entry = onlyEntry(audit);
        assertRecorded(entry, AuditActions.PAYMENT_CANCELLED, payment, NOW);
        assertSystemActor(entry, "payment-charge-compensation");
    }

    @Test
    @DisplayName("audit: abandoning a payment that is no longer PENDING records nothing")
    void audit_abandonNotPending_recordsNothing() {
        var payment = pendingPayment();
        payment.fail(NOW);
        when(payments.findById(payment.id())).thenReturn(Optional.of(payment));

        steps.abandon(payment.id());

        verifyNoInteractions(audit);
    }
}
