package com.unihub.payment.application.usecase;

import static com.unihub.payment.domain.model.PaymentBuilder.INITIATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCancelRefusedException;
import com.unihub.payment.application.port.out.GatewayCircuitOpenException;
import com.unihub.payment.application.port.out.GatewayPaymentEvent.Outcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.ReconciliationMetrics;
import com.unihub.payment.application.usecase.ReconcilePaymentSteps.Pending;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What reconciliation concludes from the gateway, one test per row of the table in payment.md §6
 * (checklist D16, AC16.4). What happens to the payment and its key once the outcome is chosen is
 * {@code PaymentResolution}'s, covered with the webhook; the real gateway statuses behind each
 * state are {@code StripePaymentGatewayIT}'s.
 */
class ReconcilePaymentTest {
    private static final Instant NOW = INITIATED_AT.plus(Duration.ofMinutes(20));
    private static final String GATEWAY_ID = "pi_123";

    private final ReconcilePaymentSteps steps = mock(ReconcilePaymentSteps.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final ReconciliationMetrics metrics = mock(ReconciliationMetrics.class);
    private final ReconciliationPolicy policy =
            new ReconciliationPolicy(Duration.ofMinutes(15), 500, Duration.ofHours(24));
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final PaymentId id = PaymentId.newId();
    private final ReconcilePaymentCommand command = new ReconcilePaymentCommand(id);

    private ReconcilePayment useCase() {
        return new ReconcilePayment(steps, gateway, metrics, policy, clock);
    }

    private void pendingWithId() {
        when(steps.findPending(id))
                .thenReturn(Optional.of(new Pending(id, GATEWAY_ID, INITIATED_AT)));
    }

    @Test
    @DisplayName("a payment already resolved is left alone, and the gateway is never asked")
    void alreadyResolved_isNoOp() {
        when(steps.findPending(id)).thenReturn(Optional.empty());

        useCase().reconcile(command);

        verifyNoInteractions(gateway);
        verify(steps, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("a gateway id that was never stored: CANCELLED, with no gateway call at all")
    void noGatewayId_isCancelledWithoutAskingTheGateway() {
        when(steps.findPending(id)).thenReturn(Optional.of(new Pending(id, null, INITIATED_AT)));

        useCase().reconcile(command);

        verify(steps).resolve(id, Outcome.CANCELLED);
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("succeeded at the gateway: SUCCEEDED")
    void succeeded() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.SUCCEEDED);

        useCase().reconcile(command);

        verify(steps).resolve(id, Outcome.SUCCEEDED);
        verify(gateway, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("an attempt was made and refused: FAILED, and nothing is cancelled")
    void declined() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.DECLINED);

        useCase().reconcile(command);

        verify(steps).resolve(id, Outcome.FAILED);
        verify(gateway, never()).cancel(any(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = GatewayPaymentState.class,
            names = {"NEVER_CONFIRMED", "CANCEL_REQUIRED"})
    @DisplayName("never confirmed, or a state this system never produces: cancel, then CANCELLED")
    void cancellable_isCancelledThenCancelled(GatewayPaymentState state) {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(state);

        useCase().reconcile(command);

        var order = org.mockito.Mockito.inOrder(gateway, steps);
        order.verify(gateway).cancel(id, GATEWAY_ID);
        order.verify(steps).resolve(id, Outcome.CANCELLED);
    }

    @Test
    @DisplayName("already cancelled at the gateway: CANCELLED, without cancelling again")
    void canceled() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.CANCELED);

        useCase().reconcile(command);

        verify(steps).resolve(id, Outcome.CANCELLED);
        verify(gateway, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("the cancel is refused because it succeeded meanwhile: resolved as SUCCEEDED")
    void cancelRefused_resolvesFromTheRealStatus() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID))
                .thenReturn(GatewayPaymentState.NEVER_CONFIRMED, GatewayPaymentState.SUCCEEDED);
        org.mockito.Mockito.doThrow(new GatewayCancelRefusedException("too late", null))
                .when(gateway)
                .cancel(id, GATEWAY_ID);

        useCase().reconcile(command);

        verify(steps).resolve(id, Outcome.SUCCEEDED);
        verify(steps, never()).resolve(id, Outcome.CANCELLED);
    }

    @Test
    @DisplayName("the cancel is refused yet the gateway still says cancellable: not guessed at")
    void cancelRefused_butStillCancellable_isAFailureToRetry() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.NEVER_CONFIRMED);
        org.mockito.Mockito.doThrow(new GatewayCancelRefusedException("no", null))
                .when(gateway)
                .cancel(id, GATEWAY_ID);

        assertThatThrownBy(() -> useCase().reconcile(command))
                .isInstanceOf(GatewayCallFailedException.class);

        verify(steps, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("still processing: stays PENDING and is never cancelled")
    void processing_staysPending() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.PROCESSING);

        useCase().reconcile(command);

        verify(steps, never()).resolve(any(), any());
        verify(gateway, never()).cancel(any(), any());
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName(
            "processing 24 h after it was initiated: still not touched, but an operator is told")
    void processing_pastTheWindow_isCountedAsStuck() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.PROCESSING);
        clock = Clock.fixed(INITIATED_AT.plus(Duration.ofHours(24)).plusMillis(1), ZoneOffset.UTC);

        useCase().reconcile(command);

        verify(metrics).stuck();
        verify(steps, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("processing for exactly 24 h is not yet stuck")
    void processing_atTheBoundary_isNotYetStuck() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.PROCESSING);
        clock = Clock.fixed(INITIATED_AT.plus(Duration.ofHours(24)), ZoneOffset.UTC);

        useCase().reconcile(command);

        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("a gateway that cannot be asked fails the message, so the broker retries it")
    void gatewayDown_propagates() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenThrow(new GatewayCallFailedException("down", null));

        assertThatThrownBy(() -> useCase().reconcile(command))
                .isInstanceOf(GatewayCallFailedException.class);

        verify(steps, never()).resolve(any(), any());
    }

    @Test
    @DisplayName(
            "circuit open on the read: skipped for this cycle, no error, payment left PENDING (payment.md §5)")
    void circuitOpen_onRetrieve_skipsTheCycle() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenThrow(new GatewayCircuitOpenException("open", null));

        useCase().reconcile(command); // returns: the message is acknowledged, not retried

        verify(steps, never()).resolve(any(), any());
        verify(gateway, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("circuit open on the cancel: also skipped, and nothing is resolved")
    void circuitOpen_onCancel_skipsTheCycle() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.NEVER_CONFIRMED);
        org.mockito.Mockito.doThrow(new GatewayCircuitOpenException("open", null))
                .when(gateway)
                .cancel(id, GATEWAY_ID);

        useCase().reconcile(command);

        verify(steps, never()).resolve(any(), any());
    }

    @Test
    @DisplayName(
            "a payment whose gateway id was never stored needs no gateway: the circuit is irrelevant")
    void noGatewayId_doesNotDependOnTheCircuit() {
        when(steps.findPending(id)).thenReturn(Optional.of(new Pending(id, null, INITIATED_AT)));
        when(gateway.retrieve(any())).thenThrow(new GatewayCircuitOpenException("open", null));

        useCase().reconcile(command);

        verify(steps).resolve(id, Outcome.CANCELLED);
    }

    @Test
    @DisplayName("a lost version race is retried in a new transaction, which finds it resolved")
    void lostRace_isRetried() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.SUCCEEDED);
        when(steps.findPending(id))
                .thenReturn(Optional.of(new Pending(id, GATEWAY_ID, INITIATED_AT)))
                .thenReturn(Optional.empty());
        when(steps.resolve(eq(id), any()))
                .thenThrow(new OptimisticConflictException("PAYMENT", id.value()));

        useCase().reconcile(command);

        verify(steps, times(1)).resolve(id, Outcome.SUCCEEDED);
        verify(gateway, times(1)).retrieve(GATEWAY_ID);
    }

    @Test
    @DisplayName("every attempt losing the race reaches the caller after three")
    void lostRace_everyTime_isThrownAfterThreeAttempts() {
        pendingWithId();
        when(gateway.retrieve(GATEWAY_ID)).thenReturn(GatewayPaymentState.SUCCEEDED);
        when(steps.resolve(eq(id), any()))
                .thenThrow(new OptimisticConflictException("PAYMENT", id.value()));

        assertThatThrownBy(() -> useCase().reconcile(command))
                .isInstanceOf(OptimisticConflictException.class);

        verify(steps, times(3)).resolve(id, Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("the policy refuses a stuck window shorter than the stale threshold")
    void policy_isValidated() {
        assertThat(policy.stuckAfter()).isGreaterThan(policy.staleAfter());
        assertThatThrownBy(
                        () ->
                                new ReconciliationPolicy(
                                        Duration.ofMinutes(15), 500, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ReconciliationPolicy(
                                        Duration.ofMinutes(15), 0, Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
