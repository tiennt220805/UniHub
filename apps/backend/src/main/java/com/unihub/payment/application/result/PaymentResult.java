package com.unihub.payment.application.result;

import com.unihub.payment.domain.model.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment as its owner sees it: the source of truth for the charge's outcome. */
public record PaymentResult(
        UUID id,
        UUID registrationId,
        BigDecimal amount,
        String currency,
        String gateway,
        String gatewayTransactionId,
        String status,
        Instant initiatedAt,
        Instant resolvedAt) {

    public static PaymentResult of(Payment p) {
        return new PaymentResult(
                p.id().value(),
                p.registrationId().value(),
                p.amount(),
                p.currency(),
                p.gateway(),
                p.gatewayTransactionId(),
                p.status().name(),
                p.initiatedAt(),
                p.resolvedAt());
    }
}
