package com.unihub.payment.application.usecase;

import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.security.Actor;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short transactions around the gateway calls of {@link RefundPayment}. A separate bean so
 * every call crosses the Spring proxy; no transaction is ever open while the gateway is asked
 * (backend-coding-standards.md §19.4).
 */
@Service
class RefundPaymentSteps {
    private static final Logger LOG = LoggerFactory.getLogger(RefundPaymentSteps.class);

    /** A payment that is owed back, as the Master has it. */
    record Owed(PaymentId id, String gatewayTransactionId) {}

    enum Effect {
        REFUNDED,
        NO_OP
    }

    private final PaymentRepository payments;
    private final PaymentAudit audit;
    private final Clock clock;

    RefundPaymentSteps(PaymentRepository payments, AuditTrail auditTrail, Clock clock) {
        this.payments = payments;
        this.audit = new PaymentAudit(auditTrail);
        this.clock = clock;
    }

    /**
     * The payment if it is still {@code REFUND_REQUIRED}, else empty: nothing to ask the gateway
     * about. Read at the Master, because a Replica that lags could show a payment that was already
     * refunded and send the consumer to the gateway for nothing.
     */
    @Transactional(readOnly = true)
    @ReadFromPrimary
    Optional<Owed> findOwed(PaymentId id) {
        var payment =
                payments.findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "refund requested for unknown payment "
                                                        + id.value()));
        return switch (payment.status()) {
            case REFUND_REQUIRED -> {
                if (payment.gatewayTransactionId() == null) {
                    // Money cannot have moved without a gateway object: nothing can be refunded.
                    throw new IllegalStateException(
                            "payment " + id.value() + " is owed back but has no gateway id");
                }
                yield Optional.of(new Owed(payment.id(), payment.gatewayTransactionId()));
            }
            case REFUNDED -> Optional.empty();
            case PENDING, SUCCEEDED, FAILED, CANCELLED -> {
                // Only the producer of this job moves a row to REFUND_REQUIRED, so this cannot
                // happen; say so loudly and do not touch the gateway.
                LOG.warn(
                        "Refund requested for payment {} in status {}; nothing to refund",
                        id.value(),
                        payment.status());
                yield Optional.empty();
            }
        };
    }

    /**
     * {@code REFUND_REQUIRED → REFUNDED}, read again in this transaction so a refund recorded
     * meanwhile is left as it is. A lost race is an {@code OptimisticConflictException} for the
     * caller to retry.
     */
    @Transactional
    Effect markRefunded(PaymentId id) {
        var payment =
                payments.findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "refund requested for unknown payment "
                                                        + id.value()));
        if (payment.status() != PaymentStatus.REFUND_REQUIRED) {
            return Effect.NO_OP;
        }
        var now = clock.instant();
        payment.refund(now);
        payments.save(payment);
        audit.moved(Actor.system(PaymentAudit.REFUND_CONSUMER), payment, now);
        return Effect.REFUNDED;
    }
}
