package com.unihub.payment.domain.event;

import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;

/**
 * The gateway took the money. Recorded only when a {@code PENDING} payment becomes {@code
 * SUCCEEDED}; a late success for a closed payment goes to the refund branch and never records it
 * (payment.md §9), so it cannot confirm a registration.
 */
public record PaymentSucceeded(
        PaymentId paymentId, RegistrationId registrationId, Instant occurredAt)
        implements PaymentDomainEvent {}
