package com.unihub.payment.application.usecase;

import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.out.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at moving a payment to {@code REFUND_REQUIRED}, in one transaction at the Master
 * (payment.md §9). The dedup point is the payment's own conditional transition, never the event:
 * the registration re-emits its rejection on every redelivery of the success, with a new event id.
 */
@Service
class RequireRefundTransaction {
    private static final Logger LOG = LoggerFactory.getLogger(RequireRefundTransaction.class);

    enum Effect {
        REFUND_REQUIRED,
        NO_OP
    }

    private final PaymentRepository payments;
    private final java.time.Clock clock;

    RequireRefundTransaction(PaymentRepository payments, java.time.Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional
    Effect attempt(RequireRefundCommand command) {
        var payment =
                payments.findById(command.paymentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "rejection for unknown payment "
                                                        + command.paymentId().value()));
        switch (payment.status()) {
            case SUCCEEDED -> {
                payment.requireRefund(clock.instant());
                payments.save(payment);
                return Effect.REFUND_REQUIRED;
            }
            case REFUND_REQUIRED, REFUNDED -> {
                // A redelivery, or the registration's repeated rejection: already on its way.
                return Effect.NO_OP;
            }
            case PENDING, FAILED, CANCELLED -> {
                // No money was taken, so there is nothing to return.
                LOG.warn(
                        "Confirmation rejected for payment {} in status {}; nothing to refund",
                        payment.id().value(),
                        payment.status());
                return Effect.NO_OP;
            }
        }
        throw new IllegalStateException("unreachable: " + payment.status());
    }
}
