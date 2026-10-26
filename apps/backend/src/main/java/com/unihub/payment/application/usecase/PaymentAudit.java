package com.unihub.payment.application.usecase;

import com.unihub.payment.domain.model.Payment;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.security.Actor;
import java.time.Instant;
import java.util.Map;

/**
 * What the Payment module tells the audit trail, in one place (checklist D6, payment.md §8): a
 * payment is audited when it is initiated and every time its status moves. A charge is money, so
 * none of it is left unrecorded. The entry is recorded inside the caller's transaction and takes
 * effect after it commits, so a rolled-back transition leaves nothing, and the trail never throws,
 * so a broken audit pipeline cannot fail the transition it describes (INV-14, fail-open).
 *
 * <p>The actor is whoever caused the change: the student for their own charge, and {@code
 * Actor.system(process)} for everything the system does by itself (webhook, reconciliation,
 * compensation, the refund consumer), never a real user.
 *
 * <p>Not a bean: the transaction beans build it from the trail they are given.
 */
final class PaymentAudit {
    static final String CHARGE_COMPENSATION = "payment-charge-compensation";
    static final String WEBHOOK = "payment-webhook";
    static final String RECONCILIATION = "payment-reconciliation";
    static final String REFUND_CONSUMER = "payment-refund";

    private final AuditTrail trail;

    PaymentAudit(AuditTrail trail) {
        this.trail = trail;
    }

    void initiated(Actor actor, Payment payment, Instant at) {
        record(actor, AuditActions.PAYMENT_INITIATED, payment, at);
    }

    /** The payment has just moved to its current status. */
    void moved(Actor actor, Payment payment, Instant at) {
        record(actor, actionFor(payment), payment, at);
    }

    private static String actionFor(Payment payment) {
        return switch (payment.status()) {
            case SUCCEEDED -> AuditActions.PAYMENT_SUCCEEDED;
            case FAILED -> AuditActions.PAYMENT_FAILED;
            case CANCELLED -> AuditActions.PAYMENT_CANCELLED;
            case REFUND_REQUIRED -> AuditActions.PAYMENT_REFUND_REQUIRED;
            case PENDING, REFUNDED ->
                    throw new IllegalStateException(
                            "no audit action for a payment that moved to " + payment.status());
        };
    }

    private void record(Actor actor, String action, Payment payment, Instant at) {
        trail.record(
                AuditEntry.of(actor, action, AuditTargets.PAYMENT, payment.id().value(), at)
                        .withMetadata(
                                Map.of(
                                        "registrationId",
                                                payment.registrationId().value().toString(),
                                        "amount", payment.amount().toPlainString(),
                                        "currency", payment.currency())));
    }
}
