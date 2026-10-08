package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import java.util.Optional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class JpaWorkshopRepository implements WorkshopRepository {
    private static final String AGGREGATE = "WORKSHOP";

    private final SpringDataWorkshopRepository springData;
    private final WorkshopPersistenceMapper mapper;

    JpaWorkshopRepository(
            SpringDataWorkshopRepository springData, WorkshopPersistenceMapper mapper) {
        this.springData = springData;
        this.mapper = mapper;
    }

    @Override
    public Optional<Workshop> findById(WorkshopId id) {
        return springData.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Workshop save(Workshop workshop) {
        var entity = springData.findById(workshop.id().value()).orElse(null);
        if (entity == null) {
            return mapper.toDomain(springData.saveAndFlush(mapper.toNewEntity(workshop)));
        }
        // Hibernate ignores a version assigned to an entity it already manages, so the aggregate's
        // version is compared here, explicitly (§9.2).
        if (entity.version() != workshop.version().value()) {
            throw new OptimisticConflictException(AGGREGATE, workshop.id().value());
        }
        mapper.copyState(workshop, entity);
        try {
            // Flushed here, not at commit: a concurrent writer that got in between the load and
            // this UPDATE (UPDATE ... WHERE id = ? AND version = ?) must surface as the shared
            // conflict exception from inside the adapter, not as a Spring exception at commit.
            return mapper.toDomain(springData.saveAndFlush(entity));
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            throw new OptimisticConflictException(AGGREGATE, workshop.id().value());
        }
    }
}
