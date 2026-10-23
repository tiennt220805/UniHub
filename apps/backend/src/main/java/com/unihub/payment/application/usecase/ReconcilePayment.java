package com.unihub.payment.application.usecase;

import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCancelRefusedException;
import com.unihub.payment.application.port.out.GatewayCircuitOpenException;
import com.unihub.payment.application.port.out.GatewayPaymentEvent.Outcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.ReconciliationMetrics;
import com.unihub.payment.application.usecase.ReconcilePaymentSteps.Pending;
import com.unihub.shared.domain.OptimisticConflictException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reconciliation's consumer (payment.md §6, checklist D16) — NOT {@code @Transactional}, so the
 * gateway is asked outside any transaction and a lost version race is retried in a new one, whose
 * re-read finds the payment already resolved.
 *
 * <p>The gateway's answer for a payment never confirmed or in a state this system never produces is
 * a cancel, then {@code CANCELLED}; for one that succeeded or was refused, the same outcome the
 * webhook would have applied; for one still {@code processing}, nothing. A payment whose gateway id
 * was never stored is cancelled with no gateway call at all: the confirm is only sent after the id
 * is stored, so it provably never moved money.
 */
@Service
class ReconcilePayment implements ReconcilePaymentUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(ReconcilePayment.class);
    private static final int ATTEMPTS = 3;

    private final ReconcilePaymentSteps steps;
    private final PaymentGateway gateway;
    private final ReconciliationMetrics metrics;
    private final ReconciliationPolicy policy;
    private final Clock clock;

    ReconcilePayment(
            ReconcilePaymentSteps steps,
            PaymentGateway gateway,
            ReconciliationMetrics metrics,
            ReconciliationPolicy policy,
            Clock clock) {
        this.steps = steps;
        this.gateway = gateway;
        this.metrics = metrics;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public void reconcile(ReconcilePaymentCommand command) {
        for (int attempt = 1; ; attempt++) {
            try {
                attemptOnce(command);
                return;
            } catch (OptimisticConflictException lostRace) {
                if (attempt >= ATTEMPTS) {
                    throw lostRace;
                }
            }
        }
    }

    private void attemptOnce(ReconcilePaymentCommand command) {
        var pending = steps.findPending(command.paymentId());
        if (pending.isEmpty()) {
            LOG.debug("Payment {} is already resolved; nothing to reconcile", command.paymentId());
            return;
        }
        Optional<Outcome> outcome;
        try {
            outcome = decide(pending.get());
        } catch (GatewayCircuitOpenException circuitOpen) {
            // Payment.md §5: skip this payment for this cycle. Throwing would send the message
            // through the retry chain into the parking lot; the payment is still PENDING, so the
            // next sweep asks again against whatever the circuit then says.
            LOG.info(
                    "The payment gateway circuit is open; payment {} is left PENDING for the next"
                            + " sweep",
                    command.paymentId().value());
            return;
        }
        if (outcome.isPresent()) {
            steps.resolve(command.paymentId(), outcome.get());
        }
    }

    /** The outcome to apply, or empty when the payment is to stay {@code PENDING}. */
    private Optional<Outcome> decide(Pending pending) {
        if (pending.gatewayTransactionId() == null) {
            return Optional.of(Outcome.CANCELLED);
        }
        var state = gateway.retrieve(pending.gatewayTransactionId());
        if (isCancellable(state)) {
            try {
                gateway.cancel(pending.id(), pending.gatewayTransactionId());
                return Optional.of(Outcome.CANCELLED);
            } catch (GatewayCancelRefusedException refused) {
                // It changed under us (it succeeded, most likely): decide from the real status.
                state = gateway.retrieve(pending.gatewayTransactionId());
                if (isCancellable(state)) {
                    throw new GatewayCallFailedException(
                            "the gateway refused to cancel a payment it still reports as " + state,
                            refused);
                }
            }
        }
        return switch (state) {
            case SUCCEEDED -> Optional.of(Outcome.SUCCEEDED);
            case DECLINED -> Optional.of(Outcome.FAILED);
            case CANCELED -> Optional.of(Outcome.CANCELLED);
            case PROCESSING -> {
                noteIfStuck(pending);
                yield Optional.empty();
            }
            case NEVER_CONFIRMED, CANCEL_REQUIRED ->
                    throw new IllegalStateException("unreachable: " + state);
        };
    }

    private static boolean isCancellable(GatewayPaymentState state) {
        return state == GatewayPaymentState.NEVER_CONFIRMED
                || state == GatewayPaymentState.CANCEL_REQUIRED;
    }

    /**
     * Past the gateway's 24 h window nothing here may act on the payment any more; an operator is
     * told, and the row stays {@code PENDING} — there is no automatic guess (payment.md §6).
     */
    private void noteIfStuck(Pending pending) {
        var age = Duration.between(pending.initiatedAt(), clock.instant());
        if (age.compareTo(policy.stuckAfter()) > 0) {
            metrics.stuck();
            LOG.warn(
                    "Payment {} is still processing at the gateway {} after it was initiated;"
                            + " an operator must look at it",
                    pending.id().value(),
                    age);
        }
    }
}
