package com.unihub.payment.application.result;

import com.unihub.payment.domain.model.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a charge request decided (checklist D1, D10). Every variant is recorded under the {@code
 * Idempotency-Key} and replayed verbatim, so the shape is a contract: a stored Result is stored as
 * its variant's simple name plus its JSON, and renaming a variant or a component breaks replay of
 * the rows already in the table (see {@code ReserveSeatResult}).
 *
 * <p>Transient failures are not variants: a {@code 503} is an exception that rolls back or deletes
 * the key, so a retry runs for real.
 */
public sealed interface ChargePaymentResult {

    /**
     * The charge step finished. A snapshot taken then: {@code PENDING} means the confirm was sent
     * and the outcome is not yet known; {@code getPayment} is the source of truth.
     */
    record Processed(
            UUID paymentId,
            UUID registrationId,
            BigDecimal amount,
            String currency,
            String gateway,
            String gatewayTransactionId,
            Status status,
            Instant initiatedAt,
            Instant resolvedAt)
            implements ChargePaymentResult {

        public static Processed of(Payment payment) {
            return new Processed(
                    payment.id().value(),
                    payment.registrationId().value(),
                    payment.amount(),
                    payment.currency(),
                    payment.gateway(),
                    payment.gatewayTransactionId(),
                    Status.valueOf(payment.status().name()),
                    payment.initiatedAt(),
                    payment.resolvedAt());
        }
    }

    /**
     * The payment's status when the snapshot was taken. Mirrors the domain's, so that {@code web}
     * can name it without touching {@code domain}; it is stored in the replayed Result, so its
     * names are as fixed as the variants'.
     */
    enum Status {
        PENDING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        REFUND_REQUIRED,
        REFUNDED
    }

    /** The registration is not {@code RESERVED}, or its hold has ended. */
    record RegistrationNotReserved() implements ChargePaymentResult {}

    /** The registration already has a live payment, possibly under another key. */
    record PaymentInProgress() implements ChargePaymentResult {}
}
