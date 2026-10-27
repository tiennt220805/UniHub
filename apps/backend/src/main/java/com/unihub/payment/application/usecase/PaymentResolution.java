package com.unihub.payment.application.usecase;

import com.unihub.payment.application.event.PaymentOutboxTranslator;
import com.unihub.payment.application.port.out.GatewayPaymentEvent.Outcome;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.security.Actor;
import java.time.Instant;

/**
 * The one mapping of "what the gateway says" onto a payment, shared by every path that learns it
 * from the gateway: the webhook and reconciliation (payment.md §6, §7, §9). It runs inside the
 * caller's transaction at the Master and begins from the status it finds, so a redelivery, or the
 * other path getting there first, is a no-op. Having one copy is the point: two copies drift.
 *
 * <p>Not a bean: the transaction beans that apply it build it from their own collaborators.
 */
final class PaymentResolution {
    private static final IdempotencyScope SCOPE = IdempotencyScope.PAYMENT_CHARGE;

    private final PaymentRepository payments;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final PaymentAudit audit;

    PaymentResolution(
            PaymentRepository payments,
            IdempotencyStore idempotency,
            EventPublisher events,
            AuditTrail auditTrail) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.events = events;
        this.audit = new PaymentAudit(auditTrail);
    }

    /**
     * Applies the outcome to the payment as found. A payment that moves is audited under {@code
     * actor}, the process that learned the outcome (webhook or reconciliation).
     *
     * @throws com.unihub.shared.domain.OptimisticConflictException when another transaction
     *     resolved the row first; the caller retries in a new transaction
     */
    PaymentWebhookTransaction.Effect apply(
            Payment payment, Outcome outcome, Instant now, Actor actor) {
        switch (payment.status()) {
            case PENDING -> {
                switch (outcome) {
                    case SUCCEEDED -> payment.succeed(now);
                    case FAILED -> payment.fail(now);
                    case CANCELLED -> payment.cancel(now);
                }
                var recorded = payment.pullEvents();
                var saved = payments.save(payment);
                recorded.stream()
                        .flatMap(e -> PaymentOutboxTranslator.translate(e).stream())
                        .forEach(events::publish);
                closeTheKey(saved);
                audit.moved(actor, saved, now);
                return PaymentWebhookTransaction.Effect.RESOLVED;
            }
            case FAILED, CANCELLED -> {
                if (outcome != Outcome.SUCCEEDED) {
                    return PaymentWebhookTransaction.Effect.NO_OP;
                }
                payment.lateSuccess(now);
                var recorded = payment.pullEvents();
                payments.save(payment);
                // Nothing else would ever refund this money (checklist D26): the job commits with
                // the transition.
                recorded.stream()
                        .flatMap(e -> PaymentOutboxTranslator.translate(e).stream())
                        .forEach(events::publish);
                audit.moved(actor, payment, now);
                return PaymentWebhookTransaction.Effect.LATE_SUCCESS;
            }
            case SUCCEEDED, REFUND_REQUIRED, REFUNDED -> {
                return PaymentWebhookTransaction.Effect.NO_OP;
            }
        }
        throw new IllegalStateException("unreachable: " + payment.status());
    }

    /**
     * Checklist G2 (D1 constraint 8): a charge whose second transaction never ran left its key
     * open; whoever resolves the payment closes it in this same transaction, so a same-key retry
     * replays the outcome instead of getting 409 forever. A payment cancelled here provably moved
     * no money, so its open key is dropped. Does nothing when no key is open.
     */
    private void closeTheKey(Payment resolved) {
        if (resolved.status() == PaymentStatus.CANCELLED) {
            idempotency.abandonOpenFor(SCOPE, resolved.id().value());
        } else {
            idempotency.completeOpenFor(
                    SCOPE, resolved.id().value(), ChargePaymentResult.Processed.of(resolved));
        }
    }
}
