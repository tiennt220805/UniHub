package com.unihub.payment.infrastructure.out.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPaymentRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByGatewayTransactionId(String gatewayTransactionId);

    boolean existsByRegistrationIdAndStatusIn(UUID registrationId, Collection<String> statuses);
}
