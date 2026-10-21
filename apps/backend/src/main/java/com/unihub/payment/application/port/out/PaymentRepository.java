package com.unihub.payment.application.port.out;

import com.unihub.payment.domain.model.Payment;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Optional;

public interface PaymentRepository {

    /** Absent is an empty Optional; the use case decides what that means. */
    Optional<Payment> findById(PaymentId id);

    /** Whether the registration has a live payment ({@code PENDING} or {@code SUCCEEDED}). */
    boolean existsLiveFor(RegistrationId registrationId);

    /**
     * Inserts a new payment, or updates an existing one if its version is still current.
     *
     * @throws com.unihub.shared.domain.OptimisticConflictException when the version is stale
     * @throws com.unihub.payment.domain.exception.LivePaymentExistsException when inserting a live
     *     payment for a registration that already has one (the partial unique index)
     */
    Payment save(Payment payment);
}
