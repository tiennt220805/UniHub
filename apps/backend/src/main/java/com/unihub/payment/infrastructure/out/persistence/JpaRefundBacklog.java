package com.unihub.payment.infrastructure.out.persistence;

import com.unihub.payment.application.port.out.RefundBacklog;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code updated_at} is set on every save and nothing saves a row while it waits in {@code
 * REFUND_REQUIRED}, so the oldest one is the time the longest-waiting refund entered that status
 * (observability.md §4).
 */
@Repository
class JpaRefundBacklog implements RefundBacklog {
    private final SpringDataPaymentRepository payments;

    JpaRefundBacklog(SpringDataPaymentRepository payments) {
        this.payments = payments;
    }

    @Override
    public Optional<Instant> oldestOwedSince() {
        return payments.oldestRefundRequiredSince();
    }
}
