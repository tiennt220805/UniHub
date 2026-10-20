package com.unihub.payment.application.port.out;

import com.unihub.payment.domain.model.Payment;
import com.unihub.shared.domain.id.PaymentId;
import java.util.Optional;

public interface PaymentRepository {

    /** Absent is an empty Optional; the use case decides what that means. */
    Optional<Payment> findById(PaymentId id);

    /**
     * Inserts a new payment, or updates an existing one if its version is still current.
     *
     * @throws com.unihub.shared.domain.OptimisticConflictException when the version is stale
     */
    Payment save(Payment payment);
}
