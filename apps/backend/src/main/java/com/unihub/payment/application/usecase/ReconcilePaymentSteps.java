package com.unihub.payment.application.usecase;

import com.unihub.payment.application.port.out.GatewayPaymentEvent.Outcome;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short transactions around the gateway calls of {@link ReconcilePayment}. A separate bean
 * so every call crosses the Spring proxy; no transaction is ever open while the gateway is asked
 * (backend-coding-standards.md §19.4).
 */
@Service
class ReconcilePaymentSteps {

    /** A payment that is still {@code PENDING}, as the Master has it. */
    record Pending(PaymentId id, String gatewayTransactionId, Instant initiatedAt) {}

    private final PaymentRepository payments;
    private final PaymentResolution resolution;
    private final Clock clock;

    ReconcilePaymentSteps(
            PaymentRepository payments,
            IdempotencyStore idempotency,
            EventPublisher events,
            AuditTrail auditTrail,
            Clock clock) {
        this.payments = payments;
        this.resolution = new PaymentResolution(payments, idempotency, events, auditTrail);
        this.clock = clock;
    }

    /**
     * The payment if it is still {@code PENDING}, else empty: nothing to ask the gateway about.
     * Read at the Master, because the webhook may have resolved it a moment ago and a lagging
     * Replica would send the consumer to the gateway for nothing.
     */
    @Transactional(readOnly = true)
    @ReadFromPrimary
    Optional<Pending> findPending(PaymentId id) {
        var payment =
                payments.findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "reconciliation for unknown payment "
                                                        + id.value()));
        if (payment.status() != PaymentStatus.PENDING) {
            return Optional.empty();
        }
        return Optional.of(
                new Pending(payment.id(), payment.gatewayTransactionId(), payment.initiatedAt()));
    }

    /**
     * Applies what the gateway said, through the mapping the webhook uses. The status is read again
     * in this transaction, so a payment the webhook resolved meanwhile is left as it is. A lost
     * race is an {@code OptimisticConflictException} for the caller to retry.
     */
    @Transactional
    PaymentWebhookTransaction.Effect resolve(PaymentId id, Outcome outcome) {
        var payment =
                payments.findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "reconciliation for unknown payment "
                                                        + id.value()));
        return resolution.apply(
                payment, outcome, clock.instant(), Actor.system(PaymentAudit.RECONCILIATION));
    }
}
