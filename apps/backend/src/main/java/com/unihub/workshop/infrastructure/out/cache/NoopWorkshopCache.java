package com.unihub.workshop.infrastructure.out.cache;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.application.port.out.WorkshopDetailSnapshot;
import com.unihub.workshop.application.port.out.WorkshopSeatsSnapshot;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Used when no Redis is configured ({@code unihub.redis.address} unset): every read misses and
 * every write is dropped, so each request goes to the database — correct, only slower
 * (distributed-coordination.md §4).
 */
@Component
@ConditionalOnExpression("'${unihub.redis.address:}'.isEmpty()")
class NoopWorkshopCache implements WorkshopCache {

    private static final Logger LOG = LoggerFactory.getLogger(NoopWorkshopCache.class);

    NoopWorkshopCache() {
        LOG.info("unihub.redis.address is not set: the workshop catalog is not cached");
    }

    @Override
    public Optional<WorkshopDetailSnapshot> findDetail(WorkshopId id) {
        return Optional.empty();
    }

    @Override
    public void putDetail(WorkshopDetailSnapshot snapshot) {}

    @Override
    public Optional<WorkshopSeatsSnapshot> findSeats(WorkshopId id) {
        return Optional.empty();
    }

    @Override
    public void putSeats(WorkshopSeatsSnapshot snapshot) {}

    @Override
    public Optional<WorkshopCatalogPage> findList(SearchWorkshopsCriteria criteria) {
        return Optional.empty();
    }

    @Override
    public void putList(SearchWorkshopsCriteria criteria, WorkshopCatalogPage page) {}

    @Override
    public void invalidate(WorkshopId id) {}

    @Override
    public void invalidateSeats(WorkshopId id) {}
}
