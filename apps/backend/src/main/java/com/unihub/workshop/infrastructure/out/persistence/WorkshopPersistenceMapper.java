package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.domain.model.WorkshopStatus;
import org.springframework.stereotype.Component;

@Component
class WorkshopPersistenceMapper {

    Workshop toDomain(WorkshopJpaEntity e) {
        return Workshop.restore(
                WorkshopId.of(e.id()),
                e.title(),
                e.description(),
                e.aiSummary(),
                e.speakerName(),
                e.speakerBio(),
                e.location(),
                e.startTime(),
                e.endTime(),
                e.capacity(),
                e.seatsTaken(),
                e.paid(),
                e.price(),
                e.brochureS3Key(),
                WorkshopStatus.valueOf(e.status()),
                new Version(e.version()),
                e.createdAt(),
                e.updatedAt());
    }

    WorkshopJpaEntity toNewEntity(Workshop w) {
        var entity = new WorkshopJpaEntity();
        entity.id(w.id().value());
        copyState(w, entity);
        return entity;
    }

    /** Copies every column the aggregate owns; never {@code version}, which Hibernate manages. */
    void copyState(Workshop w, WorkshopJpaEntity e) {
        e.title(w.title());
        e.description(w.description());
        e.aiSummary(w.aiSummary());
        e.speakerName(w.speakerName());
        e.speakerBio(w.speakerBio());
        e.location(w.location());
        e.startTime(w.startTime());
        e.endTime(w.endTime());
        e.capacity(w.capacity());
        e.seatsTaken(w.seatsTaken());
        e.paid(w.isPaid());
        e.price(w.price());
        e.brochureS3Key(w.brochureS3Key());
        e.status(w.status().name());
    }
}
