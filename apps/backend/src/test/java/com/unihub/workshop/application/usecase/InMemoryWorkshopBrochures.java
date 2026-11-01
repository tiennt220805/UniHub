package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A map-backed {@link WorkshopBrochures} for use case tests: the same compare-and-set rules as the
 * SQL.
 */
final class InMemoryWorkshopBrochures implements WorkshopBrochures {
    final Map<WorkshopId, String> keys = new HashMap<>();
    final Map<WorkshopId, String> pending = new HashMap<>();
    final Map<WorkshopId, String> summaries = new HashMap<>();

    @Override
    public Optional<String> currentKey(WorkshopId workshopId) {
        return Optional.ofNullable(keys.get(workshopId));
    }

    @Override
    public boolean recordSummary(WorkshopId workshopId, String brochureKey, String summary) {
        if (!brochureKey.equals(keys.get(workshopId))) {
            return false;
        }
        summaries.put(workshopId, summary);
        return true;
    }

    @Override
    public void issue(WorkshopId workshopId, String pendingKey) {
        pending.put(workshopId, pendingKey);
    }

    @Override
    public Optional<String> pendingKey(WorkshopId workshopId) {
        return Optional.ofNullable(pending.get(workshopId));
    }

    @Override
    public boolean confirm(WorkshopId workshopId, String pendingKey) {
        if (!pendingKey.equals(pending.get(workshopId))) {
            return false;
        }
        pending.remove(workshopId);
        keys.put(workshopId, pendingKey);
        return true;
    }
}
