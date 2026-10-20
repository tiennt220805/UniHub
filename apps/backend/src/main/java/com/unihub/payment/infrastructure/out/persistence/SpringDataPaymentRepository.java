package com.unihub.payment.infrastructure.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPaymentRepository extends JpaRepository<PaymentJpaEntity, UUID> {}
