package com.unihub.payment.application.usecase;

import com.unihub.payment.application.port.out.GatewayPaymentEvent;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.shared.application.port.out.EventPublisher;
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

    private final PaymentRepository payments;
    private final PaymentResolution resolution;
    private final Clock clock;

    PaymentWebhookTransaction(
            PaymentRepository payments,
            IdempotencyStore idempotency,
            EventPublisher events,
            Clock clock) {
        this.payments = payments;
        this.resolution = new PaymentResolution(payments, idempotency, events);
        this.clock = clock;
    }

    @Transactional
    Effect apply(GatewayPaymentEvent event) {
        var found = payments.findByGatewayTransactionId(event.gatewayTransactionId());
        if (found.isEmpty()) {
            return Effect.UNKNOWN_TRANSACTION;
        }
        return resolution.apply(found.get(), event.outcome(), clock.instant());
    }
}
