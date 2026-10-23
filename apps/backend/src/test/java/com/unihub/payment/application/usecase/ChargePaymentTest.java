package com.unihub.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayAdmission;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.GatewayOutcomeUnknownException;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentMethodRejectedException;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Decided;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Resolution;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Started;
import com.unihub.payment.domain.exception.LivePaymentExistsException;
import com.unihub.payment.domain.exception.PaymentGatewayUnavailableException;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyRecord;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.port.out.StoredResult;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.security.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The branches of the charge orchestration (backend-coding-standards.md §19.4): which step runs
 * after which gateway answer. The gateway, the steps and the breaker are doubles; what is proved is
 * the decision table, in particular that nothing is ever cancelled once a confirm may have been
 * sent.
 */
class ChargePaymentTest {
    private static final UserId STUDENT = UserId.newId();
    private static final RegistrationId REGISTRATION = RegistrationId.newId();
    private static final PaymentId PAYMENT = PaymentId.newId();
    private static final String KEY = UUID.randomUUID().toString();
    private static final ChargePaymentCommand COMMAND =
            new ChargePaymentCommand(
                    new Actor.UserActor(STUDENT, Set.of()), REGISTRATION, "pm_card_visa", KEY, "f");
    private static final Started STARTED = new Started(PAYMENT, new BigDecimal("150000"), "VND");
    private static final ChargePaymentResult.Processed PROCESSED =
            new ChargePaymentResult.Processed(
                    PAYMENT.value(),
                    REGISTRATION.value(),
                    new BigDecimal("150000"),
                    "VND",
                    "STRIPE",
                    "pi_1",
                    ChargePaymentResult.Status.SUCCEEDED,
                    Instant.parse("2026-12-01T09:00:00Z"),
                    Instant.parse("2026-12-01T09:00:02Z"));

    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final GatewayAdmission admission = mock(GatewayAdmission.class);
    private final ChargePaymentSteps steps = mock(ChargePaymentSteps.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final ChargePayment charge = new ChargePayment(idempotency, admission, steps, gateway);

    private void started() {
        when(admission.admitting()).thenReturn(true);
        when(steps.begin(COMMAND)).thenReturn(STARTED);
        when(steps.resolve(eq(COMMAND), eq(PAYMENT), any())).thenReturn(PROCESSED);
    }

    private void createdAs(String gatewayId) {
        when(gateway.create(PAYMENT, STARTED.amount(), "VND", "pm_card_visa"))
                .thenReturn(gatewayId);
    }

    // ---------- before any write ----------

    @Test
    @DisplayName(
            "a recorded Result is replayed before the breaker or the gateway is asked anything")
    void replay_touchesNothing() {
        when(idempotency.find(IdempotencyScope.PAYMENT_CHARGE, STUDENT, KEY))
                .thenReturn(Optional.of(completed(PROCESSED)));
        when(idempotency.decode(any(), eq(ChargePaymentResult.class))).thenReturn(PROCESSED);

        assertThat(charge.charge(COMMAND)).isEqualTo(PROCESSED);

        verifyNoInteractions(admission, steps, gateway);
    }

    @Test
    @DisplayName("a refused admission is a 503 and nothing is written (AC1.9)")
    void admissionRefused_is503_writesNothing() {
        when(admission.admitting()).thenReturn(false);

        assertThatThrownBy(() -> charge.charge(COMMAND))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        verifyNoInteractions(steps, gateway);
    }

    @Test
    @DisplayName("a request settled without the gateway never reaches it")
    void decidedWithoutGateway_neverCallsTheGateway() {
        when(admission.admitting()).thenReturn(true);
        when(steps.begin(COMMAND))
                .thenReturn(new Decided(new ChargePaymentResult.RegistrationNotReserved()));

        var result = charge.charge(COMMAND);

        assertThat(result).isEqualTo(new ChargePaymentResult.RegistrationNotReserved());
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("losing the live-payment index is recorded as 'in progress', gateway untouched")
    void lostTheIndex_isRecordedAsInProgress() {
        when(admission.admitting()).thenReturn(true);
        when(steps.begin(COMMAND))
                .thenThrow(new LivePaymentExistsException(REGISTRATION, new RuntimeException()));
        when(steps.recordInProgress(COMMAND))
                .thenReturn(new Decided(new ChargePaymentResult.PaymentInProgress()));

        assertThat(charge.charge(COMMAND)).isEqualTo(new ChargePaymentResult.PaymentInProgress());

        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("a request already in flight under the key is not retried by the orchestrator")
    void inFlight_propagates() {
        when(idempotency.find(any(), any(), any())).thenReturn(Optional.of(inProgress()));

        assertThatThrownBy(() -> charge.charge(COMMAND))
                .isInstanceOf(IdempotencyKeyInUseException.class);

        verifyNoInteractions(admission, steps, gateway);
    }

    // ---------- create: moves no money ----------

    @Test
    @DisplayName("any failure of create cancels the payment, drops the key and answers 503")
    void createFails_abandons() {
        started();
        when(gateway.create(any(), any(), any(), any()))
                .thenThrow(new GatewayCallFailedException("down", null));

        assertThatThrownBy(() -> charge.charge(COMMAND))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        verify(steps).abandon(PAYMENT);
        verify(gateway, never()).confirm(any(), any());
        verify(steps, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("a refused payment method token is a FAILED payment, not a 503")
    void tokenRefused_isFailed() {
        started();
        when(gateway.create(any(), any(), any(), any()))
                .thenThrow(new PaymentMethodRejectedException("no such pm", null));

        charge.charge(COMMAND);

        verify(steps).resolve(COMMAND, PAYMENT, Resolution.FAILED);
        verify(steps, never()).abandon(any());
        verify(gateway, never()).confirm(any(), any());
    }

    @Test
    @DisplayName("an unexpected error in create undoes the payment and is not hidden")
    void createBug_abandonsAndRethrows() {
        started();
        var bug = new IllegalArgumentException("three-decimal currency");
        when(gateway.create(any(), any(), any(), any())).thenThrow(bug);

        assertThatThrownBy(() -> charge.charge(COMMAND)).isSameAs(bug);

        verify(steps).abandon(PAYMENT);
    }

    @Test
    @DisplayName("failing to store the gateway id cancels the payment: the confirm is never sent")
    void attachFails_abandons_neverConfirms() {
        started();
        createdAs("pi_1");
        doThrow(new IllegalStateException("db down")).when(steps).attach(PAYMENT, "pi_1");

        assertThatThrownBy(() -> charge.charge(COMMAND))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        verify(steps).abandon(PAYMENT);
        verify(gateway, never()).confirm(any(), any());
    }

    // ---------- confirm: the only call that moves money ----------

    @Test
    @DisplayName("the calls happen in order: create, store the id, confirm, resolve")
    void order() {
        started();
        createdAs("pi_1");
        when(gateway.confirm(PAYMENT, "pi_1")).thenReturn(new ConfirmOutcome.Succeeded());

        charge.charge(COMMAND);

        InOrder order = inOrder(steps, gateway);
        order.verify(steps).begin(COMMAND);
        order.verify(gateway).create(any(), any(), any(), any());
        order.verify(steps).attach(PAYMENT, "pi_1");
        order.verify(gateway).confirm(PAYMENT, "pi_1");
        order.verify(steps).resolve(COMMAND, PAYMENT, Resolution.SUCCEEDED);
    }

    @Test
    @DisplayName("each answer of the gateway settles the payment one way")
    void answers() {
        started();
        createdAs("pi_1");

        when(gateway.confirm(PAYMENT, "pi_1"))
                .thenReturn(new ConfirmOutcome.Declined("insufficient"));
        charge.charge(COMMAND);
        verify(steps).resolve(COMMAND, PAYMENT, Resolution.FAILED);

        when(gateway.confirm(PAYMENT, "pi_1")).thenReturn(new ConfirmOutcome.Processing());
        charge.charge(COMMAND);
        verify(steps).resolve(COMMAND, PAYMENT, Resolution.INITIATED);
    }

    @Test
    @DisplayName("a confirm that never left UniHub cancels the payment and answers 503")
    void confirmNeverLeft_abandons() {
        started();
        createdAs("pi_1");
        when(gateway.confirm(PAYMENT, "pi_1")).thenThrow(new GatewayNotReachedException("x", null));

        assertThatThrownBy(() -> charge.charge(COMMAND))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        verify(steps).abandon(PAYMENT);
        verify(steps, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("a confirm that may have been sent is never cancelled: the payment stays PENDING")
    void confirmMaybeSent_staysPending_neverAbandons() {
        started();
        createdAs("pi_1");
        when(gateway.confirm(PAYMENT, "pi_1"))
                .thenThrow(new GatewayOutcomeUnknownException("read timeout", null));

        charge.charge(COMMAND);

        verify(steps).resolve(COMMAND, PAYMENT, Resolution.INITIATED);
        verify(steps, never()).abandon(any());
    }

    @Test
    @DisplayName("an unexpected error from confirm counts as possibly sent, never as 'not sent'")
    void confirmUnexpectedError_staysPending() {
        started();
        createdAs("pi_1");
        when(gateway.confirm(PAYMENT, "pi_1")).thenThrow(new IllegalStateException("?"));

        charge.charge(COMMAND);

        verify(steps).resolve(COMMAND, PAYMENT, Resolution.INITIATED);
        verify(steps, never()).abandon(any());
    }

    private static IdempotencyRecord completed(Object result) {
        return new IdempotencyRecord.Completed("f", new StoredResult("Processed", "{}"));
    }

    private static IdempotencyRecord inProgress() {
        return new IdempotencyRecord.InProgress("f");
    }
}
