package com.unihub.payment.application.usecase;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayAdmission;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.payment.application.port.out.PaymentMethodRejectedException;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Begun;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Decided;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Resolution;
import com.unihub.payment.application.usecase.ChargePaymentSteps.Started;
import com.unihub.payment.domain.exception.LivePaymentExistsException;
import com.unihub.payment.domain.exception.PaymentGatewayUnavailableException;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.usecase.IdempotentReplay;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a charge (backend-coding-standards.md §19.4, payment.md §3, §8) — deliberately NOT
 * {@code @Transactional}: no database transaction is open while the gateway is being called.
 *
 * <pre>
 * replay → gateway admission → begin → create → attach → confirm → resolve
 *                                 └ abandon (503) when nothing could have been charged
 * </pre>
 *
 * Only {@code confirm} can move money. Anything that fails before it, or proves it never left
 * UniHub, cancels the payment and deletes the key. A confirm that may have been sent keeps the
 * payment {@code PENDING} and completes the key, so the confirm is never sent twice.
 */
@Service
class ChargePayment implements ChargePaymentUseCase {
    private static final String UNAVAILABLE = "The payment gateway is temporarily unavailable.";

    private final IdempotencyStore idempotency;
    private final GatewayAdmission admission;
    private final ChargePaymentSteps steps;
    private final PaymentGateway gateway;

    ChargePayment(
            IdempotencyStore idempotency,
            GatewayAdmission admission,
            ChargePaymentSteps steps,
            PaymentGateway gateway) {
        this.idempotency = idempotency;
        this.admission = admission;
        this.steps = steps;
        this.gateway = gateway;
    }

    @Override
    public ChargePaymentResult charge(ChargePaymentCommand command) {
        // A retry is answered from the recorded Result before anything else is asked of anyone.
        var replay =
                IdempotentReplay.of(
                        idempotency,
                        IdempotencyScope.PAYMENT_CHARGE,
                        command.actor().userId(),
                        command.idempotencyKey(),
                        command.requestFingerprint(),
                        ChargePaymentResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!admission.tryAcquire()) {
            throw new PaymentGatewayUnavailableException(UNAVAILABLE, null);
        }
        var gatewayCalled = false;
        try {
            Begun begun;
            try {
                begun = steps.begin(command);
            } catch (LivePaymentExistsException lostTheIndex) {
                return steps.recordInProgress(command).result();
            }
            if (begun instanceof Decided decided) {
                return decided.result();
            }
            gatewayCalled = true;
            return callGateway(command, (Started) begun);
        } finally {
            if (!gatewayCalled) {
                admission.release();
            }
        }
    }

    private ChargePaymentResult callGateway(ChargePaymentCommand command, Started started) {
        var paymentId = started.paymentId();

        String gatewayId;
        try {
            gatewayId =
                    gateway.create(
                            paymentId,
                            started.amount(),
                            started.currency(),
                            command.paymentMethodToken());
        } catch (PaymentMethodRejectedException rejected) {
            // Nothing charged and a retry could never succeed: a decided outcome, not a 503.
            return steps.resolve(command, paymentId, Resolution.FAILED);
        } catch (GatewayCallFailedException failed) {
            steps.abandon(paymentId);
            throw new PaymentGatewayUnavailableException(UNAVAILABLE, failed);
        } catch (RuntimeException unexpected) {
            // Nothing was confirmed, so nothing was charged: undo, and let the bug surface.
            steps.abandon(paymentId);
            throw unexpected;
        }

        try {
            steps.attach(paymentId, gatewayId);
        } catch (RuntimeException storeFailed) {
            steps.abandon(paymentId);
            throw new PaymentGatewayUnavailableException(UNAVAILABLE, storeFailed);
        }

        ConfirmOutcome outcome;
        try {
            outcome = gateway.confirm(paymentId, gatewayId);
        } catch (GatewayNotReachedException neverLeft) {
            steps.abandon(paymentId);
            throw new PaymentGatewayUnavailableException(UNAVAILABLE, neverLeft);
        } catch (RuntimeException maybeSent) {
            // GatewayOutcomeUnknownException, and anything else unexpected, may have been sent:
            // keep it PENDING for the webhook or reconciliation.
            return steps.resolve(command, paymentId, Resolution.INITIATED);
        }
        return steps.resolve(command, paymentId, resolutionOf(outcome));
    }

    private static Resolution resolutionOf(ConfirmOutcome outcome) {
        return switch (outcome) {
            case ConfirmOutcome.Succeeded succeeded -> Resolution.SUCCEEDED;
            case ConfirmOutcome.Declined declined -> Resolution.FAILED;
            case ConfirmOutcome.Processing processing -> Resolution.INITIATED;
        };
    }
}
