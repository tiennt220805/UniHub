package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.application.port.out.WorkshopDetailSnapshot;
import com.unihub.workshop.application.port.out.WorkshopSeatsSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A map-backed {@link WorkshopCache} for use case tests; no TTL, and it records what was dropped.
 */
final class InMemoryWorkshopCache implements WorkshopCache {

    final Map<UUID, WorkshopDetailSnapshot> details = new HashMap<>();
    final Map<UUID, WorkshopSeatsSnapshot> seats = new HashMap<>();
    final Map<SearchWorkshopsCriteria, WorkshopCatalogPage> lists = new HashMap<>();
    final List<UUID> invalidated = new ArrayList<>();
    final List<UUID> seatsInvalidated = new ArrayList<>();

    @Override
    public Optional<WorkshopDetailSnapshot> findDetail(WorkshopId id) {
        return Optional.ofNullable(details.get(id.value()));
    }

    @Override
    public void putDetail(WorkshopDetailSnapshot snapshot) {
        details.put(snapshot.id(), snapshot);
    }

    @Override
    public Optional<WorkshopSeatsSnapshot> findSeats(WorkshopId id) {
        return Optional.ofNullable(seats.get(id.value()));
    }

    @Override
    public void putSeats(WorkshopSeatsSnapshot snapshot) {
        seats.put(snapshot.id(), snapshot);
    }

    @Override
    public Optional<WorkshopCatalogPage> findList(SearchWorkshopsCriteria criteria) {
        return Optional.ofNullable(lists.get(criteria));
    }

    @Override
    public void putList(SearchWorkshopsCriteria criteria, WorkshopCatalogPage page) {
        lists.put(criteria, page);
    }

    @Override
    public void invalidate(WorkshopId id) {
        invalidated.add(id.value());
        details.remove(id.value());
        seats.remove(id.value());
    }

    @Override
    public void invalidateSeats(WorkshopId id) {
        seatsInvalidated.add(id.value());
        seats.remove(id.value());
    }
}
