package com.unihub.payment.infrastructure.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPaymentRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByGatewayTransactionId(String gatewayTransactionId);

    boolean existsByRegistrationIdAndStatusIn(UUID registrationId, Collection<String> statuses);

    /** Rides {@code idx_payments_pending_scan} (initiated_at WHERE status = 'PENDING'). */
    @Query(
            "select p.id from PaymentJpaEntity p where p.status = 'PENDING'"
                    + " and p.initiatedAt < :cutoff order by p.initiatedAt asc, p.id asc")
    List<UUID> findPendingInitiatedBefore(@Param("cutoff") Instant cutoff, Pageable page);
}
