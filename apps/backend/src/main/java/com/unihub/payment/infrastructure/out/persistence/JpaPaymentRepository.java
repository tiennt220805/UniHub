package com.unihub.payment.infrastructure.out.persistence;

import com.unihub.payment.application.port.out.PaymentRepository;
import com.unihub.payment.domain.exception.LivePaymentExistsException;
import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class JpaPaymentRepository implements PaymentRepository {
    private static final String AGGREGATE = "PAYMENT";
    private static final String LIVE_PER_REGISTRATION_INDEX = "uq_payments_live_per_registration";
    private static final List<String> LIVE_STATUSES =
            Arrays.stream(PaymentStatus.values())
                    .filter(PaymentStatus::isLive)
                    .map(Enum::name)
                    .toList();

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
    public Optional<Payment> findByGatewayTransactionId(String gatewayTransactionId) {
        return springData.findByGatewayTransactionId(gatewayTransactionId).map(mapper::toDomain);
    }

    @Override
    public boolean existsLiveFor(RegistrationId registrationId) {
        return springData.existsByRegistrationIdAndStatusIn(registrationId.value(), LIVE_STATUSES);
    }

    @Override
    public Payment save(Payment payment) {
        var entity = springData.findById(payment.id().value()).orElse(null);
        if (entity == null) {
            return insert(payment);
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

    /**
     * The partial unique index is not a target for {@code ON CONFLICT ON CONSTRAINT}, so the
     * violation is read off the exception (checklist D5). It is a domain failure; any other
     * integrity error is not ours to hide.
     */
    private Payment insert(Payment payment) {
        try {
            return mapper.toDomain(springData.saveAndFlush(mapper.toNewEntity(payment)));
        } catch (DataIntegrityViolationException violation) {
            if (violation.getMostSpecificCause().getMessage() != null
                    && violation
                            .getMostSpecificCause()
                            .getMessage()
                            .contains(LIVE_PER_REGISTRATION_INDEX)) {
                throw new LivePaymentExistsException(payment.registrationId(), violation);
            }
            throw violation;
        }
    }
}
