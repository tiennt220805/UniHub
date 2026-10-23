package com.unihub.payment.application.port.out;

import com.unihub.shared.domain.id.PaymentId;
import java.math.BigDecimal;

/**
 * The Payment Gateway (payment.md §3, backend-coding-standards.md §19.4). Only Payment calls it.
 * One method per gateway call, so each call can be timed, counted and decorated on its own
 * (checklist D17). No method retries: every retry of a step uses the same gateway idempotency key,
 * and the orchestrator, not the adapter, decides what a failure means.
 *
 * <p>The charge is split so that only {@link #confirm} can move money (checklist D16).
 * Reconciliation reads the gateway's record with {@link #retrieve} and, for a charge that was
 * provably never confirmed, closes it with {@link #cancel} (payment.md §6).
 */
public interface PaymentGateway {

    /**
     * Creates the gateway's payment object <em>unconfirmed</em>: this moves no money, so calling it
     * again is always safe. Returns the gateway's id for it.
     *
     * @throws PaymentMethodRejectedException when the gateway refuses the payment method token
     * @throws GatewayCallFailedException on any other failure, a read timeout included: nothing was
     *     confirmed, so no money can have moved
     */
    String create(
            PaymentId paymentId, BigDecimal amount, String currency, String paymentMethodToken);

    /**
     * Confirms the object created by {@link #create}: the one call that can charge the card.
     *
     * @throws GatewayNotReachedException when the request provably never left UniHub
     * @throws GatewayOutcomeUnknownException when it may have been sent and the answer is unusable
     */
    ConfirmOutcome confirm(PaymentId paymentId, String gatewayTransactionId);

    /**
     * Reads the gateway's record of the payment by id: a plain read, never a search (payment.md
     * §6).
     *
     * @throws GatewayCallFailedException when the gateway cannot be asked, or answers with a status
     *     this system does not know: nothing is guessed, the caller tries again later
     */
    GatewayPaymentState retrieve(String gatewayTransactionId);

    /**
     * Cancels a payment that was never confirmed (or is in a state that cannot occur here). Uses
     * the same gateway key on every retry, so asking twice is asking once.
     *
     * @throws GatewayCancelRefusedException when the gateway says it can no longer be cancelled
     * @throws GatewayCallFailedException on any other failure
     */
    void cancel(PaymentId paymentId, String gatewayTransactionId);
}
