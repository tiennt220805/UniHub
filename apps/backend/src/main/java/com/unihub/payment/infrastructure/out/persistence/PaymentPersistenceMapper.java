package com.unihub.payment.infrastructure.out.persistence;

import com.unihub.payment.domain.model.Payment;
import com.unihub.payment.domain.model.PaymentStatus;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import org.springframework.stereotype.Component;

@Component
class PaymentPersistenceMapper {

    Payment toDomain(PaymentJpaEntity e) {
        return Payment.restore(
                PaymentId.of(e.id()),
                RegistrationId.of(e.registrationId()),
                e.amount(),
                e.currency(),
                e.gateway(),
                e.gatewayTransactionId(),
                PaymentStatus.valueOf(e.status()),
                e.initiatedAt(),
                e.resolvedAt(),
                new Version(e.version()));
    }

    PaymentJpaEntity toNewEntity(Payment p) {
        var entity = new PaymentJpaEntity();
        entity.id(p.id().value());
        entity.registrationId(p.registrationId().value());
        entity.amount(p.amount());
        entity.currency(p.currency());
        entity.gateway(p.gateway());
        entity.initiatedAt(p.initiatedAt());
        copyState(p, entity);
        return entity;
    }

    /** The columns a transition may change; never {@code version}, which Hibernate manages. */
    void copyState(Payment p, PaymentJpaEntity e) {
        e.gatewayTransactionId(p.gatewayTransactionId());
        e.status(p.status().name());
        e.resolvedAt(p.resolvedAt());
    }
}
