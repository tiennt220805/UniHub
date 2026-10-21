package com.unihub.payment.infrastructure.out.persistence;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPaymentRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    boolean existsByRegistrationIdAndStatusIn(UUID registrationId, Collection<String> statuses);
}
