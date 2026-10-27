package com.unihub.payment.domain.event;

import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;

/**
 * The money of this payment is owed back: it reached {@code REFUND_REQUIRED}, whether from {@code
 * SUCCEEDED} (its registration could not be confirmed) or straight from {@code FAILED}/{@code
 * CANCELLED} (a late success). Becomes the refund job Payment sends to itself (payment.md §9).
 */
public record RefundRequired(PaymentId paymentId, RegistrationId registrationId, Instant occurredAt)
        implements PaymentDomainEvent {}
