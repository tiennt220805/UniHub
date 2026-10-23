package com.unihub.payment.application.usecase;

import com.unihub.payment.application.event.PaymentReconciliationRequested;
import com.unihub.payment.application.port.in.RequestPaymentReconciliationsUseCase;
import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.shared.application.port.out.EventPublisher;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sweep's fast, database-only half (payment.md §6): the rows and their outbox messages commit
 * together, and the slow gateway call per row happens later, in the consumer. A payment still
 * {@code PENDING} at the next sweep is requested again, which costs a redundant gateway read and
 * nothing else — the consumer re-checks the status.
 */
@Service
class RequestPaymentReconciliations implements RequestPaymentReconciliationsUseCase {

    private final PaymentRepository payments;
    private final EventPublisher events;
    private final ReconciliationPolicy policy;
    private final Clock clock;

    RequestPaymentReconciliations(
            PaymentRepository payments,
            EventPublisher events,
            ReconciliationPolicy policy,
            Clock clock) {
        this.payments = payments;
        this.events = events;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int requestReconciliations() {
        var now = clock.instant();
        var stale =
                payments.findPendingInitiatedBefore(
                        now.minus(policy.staleAfter()), policy.sweepBatchSize());
        for (var paymentId : stale) {
            events.publish(new PaymentReconciliationRequested(paymentId.value(), now));
        }
        return stale.size();
    }
}
