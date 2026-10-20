package com.unihub.registration.infrastructure.out.persistence;

import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.registration.domain.model.TicketType;
import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import org.springframework.stereotype.Component;

@Component
class RegistrationPersistenceMapper {

    Registration toDomain(RegistrationJpaEntity e) {
        return Registration.restore(
                RegistrationId.of(e.id()),
                WorkshopId.of(e.workshopId()),
                UserId.of(e.userId()),
                TicketType.valueOf(e.ticketType()),
                RegistrationStatus.valueOf(e.status()),
                e.holdExpiresAt(),
                e.ticketCode(),
                e.qrPayload(),
                new Version(e.version()),
                e.createdAt(),
                e.updatedAt());
    }

    RegistrationJpaEntity toNewEntity(Registration r) {
        var entity = new RegistrationJpaEntity();
        entity.id(r.id().value());
        entity.workshopId(r.workshopId().value());
        entity.userId(r.userId().value());
        entity.ticketType(r.ticketType().name());
        copyState(r, entity);
        return entity;
    }

    /** The columns a transition may change; never {@code version}, which Hibernate manages. */
    void copyState(Registration r, RegistrationJpaEntity e) {
        e.status(r.status().name());
        e.holdExpiresAt(r.holdExpiresAt());
        e.ticketCode(r.ticketCode());
        e.qrPayload(r.qrPayload());
    }
}
