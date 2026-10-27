package com.unihub.payment.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** What an operator watches the refunds owed by (payment.md §9, observability.md §4). */
public interface RefundBacklog {

    /**
     * When the oldest payment still {@code REFUND_REQUIRED} entered that status, or empty when none
     * is owed ({@code unihub_payment_refund_required_oldest_age_seconds}).
     */
    Optional<Instant> oldestOwedSince();
}
