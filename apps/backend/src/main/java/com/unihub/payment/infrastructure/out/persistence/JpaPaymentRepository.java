package com.unihub.payment.infrastructure.out.persistence;

import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.domain.model.Payment;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import java.util.Optional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class JpaPaymentRepository implements PaymentRepository {
    private static final String AGGREGATE = "PAYMENT";

    private final SpringDataPaymentRepository springData;
    private final PaymentPersistenceMapper mapper;

    JpaPaymentRepository(SpringDataPaymentRepository springData, PaymentPersistenceMapper mapper) {
        this.springData = springData;
        this.mapper = mapper;
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        return springData.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Payment save(Payment payment) {
        var entity = springData.findById(payment.id().value()).orElse(null);
        if (entity == null) {
            return mapper.toDomain(springData.saveAndFlush(mapper.toNewEntity(payment)));
        }
        // Hibernate ignores a version assigned to a managed entity (§9.2): compare explicitly.
        if (entity.version() != payment.version().value()) {
            throw new OptimisticConflictException(AGGREGATE, payment.id().value());
        }
        mapper.copyState(payment, entity);
        try {
            return mapper.toDomain(springData.saveAndFlush(entity));
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            throw new OptimisticConflictException(AGGREGATE, payment.id().value());
        }
    }
}
