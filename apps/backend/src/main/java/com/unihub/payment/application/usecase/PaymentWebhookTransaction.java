package com.unihub.payment.application.usecase;

import com.unihub.payment.application.event.PaymentOutboxTranslator;
import com.unihub.payment.application.port.out.GatewayPaymentEvent;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at applying a verified gateway event to its payment, in one transaction at the Master
 * (payment.md §7, §6 "one mapping for what the gateway says"). Every branch begins by reading the
 * current status, so a redelivery, or the sweep or the charge's own second transaction getting
 * there first, is a no-op. A lost race is an {@code OptimisticConflictException} for the caller to
 * retry.
 */
@Service
class PaymentWebhookTransaction {

    enum Effect {
        /** The row was {@code PENDING} and now carries the gateway's outcome. */
        RESOLVED,
        /** The gateway reported a success for a payment already closed: refund, not confirm. */
        LATE_SUCCESS,
        /** Nothing to do: already resolved, or the outcome adds nothing. */
        NO_OP,
        /** No payment has this gateway transaction id. */
        UNKNOWN_TRANSACTION
    }

    private static final IdempotencyScope SCOPE = IdempotencyScope.PAYMENT_CHARGE;

    private final PaymentRepository payments;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    PaymentWebhookTransaction(
            PaymentRepository payments,
            IdempotencyStore idempotency,
            EventPublisher events,
            Clock clock) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    Effect apply(GatewayPaymentEvent event) {
        var found = payments.findByGatewayTransactionId(event.gatewayTransactionId());
        if (found.isEmpty()) {
            return Effect.UNKNOWN_TRANSACTION;
        }
        var payment = found.get();
        var now = clock.instant();
        switch (payment.status()) {
            case PENDING -> {
                switch (event.outcome()) {
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
                return Effect.RESOLVED;
            }
            case FAILED, CANCELLED -> {
                if (event.outcome() != GatewayPaymentEvent.Outcome.SUCCEEDED) {
                    return Effect.NO_OP;
                }
                payment.lateSuccess(now);
                payments.save(payment);
                return Effect.LATE_SUCCESS;
            }
            case SUCCEEDED, REFUND_REQUIRED, REFUNDED -> {
                return Effect.NO_OP;
            }
        }
        throw new IllegalStateException("unreachable: " + payment.status());
    }

    /**
     * Checklist G2 (D1 constraint 8): a charge whose second transaction never ran left its key
     * open; the webhook resolving the payment closes it in this same transaction, so a same-key
     * retry replays the outcome instead of getting 409 forever. A payment the webhook cancels
     * provably moved no money, so its open key is dropped. Does nothing when no key is open.
     */
    private void closeTheKey(com.unihub.payment.domain.model.Payment resolved) {
        if (resolved.status() == PaymentStatus.CANCELLED) {
            idempotency.abandonOpenFor(SCOPE, resolved.id().value());
        } else {
            idempotency.completeOpenFor(
                    SCOPE, resolved.id().value(), ChargePaymentResult.Processed.of(resolved));
        }
    }
}
