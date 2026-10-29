package com.unihub.notification.infrastructure.out.reads;

import com.unihub.notification.application.port.out.WorkshopDetails;
import com.unihub.notification.application.port.out.WorkshopFacts;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.WorkshopSummaries;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link WorkshopDetails} over {@code workshop.api} (Phase 4 checklist D35): translation only. */
@Component
class WorkshopApiWorkshopDetails implements WorkshopDetails {
    private final WorkshopSummaries summaries;

    WorkshopApiWorkshopDetails(WorkshopSummaries summaries) {
        this.summaries = summaries;
    }

    @Override
    public Optional<WorkshopFacts> find(UUID workshopId) {
        return summaries
                .find(WorkshopId.of(workshopId))
                .map(
                        summary ->
                                new WorkshopFacts(
                                        summary.id().value(),
                                        summary.title(),
                                        summary.startTime(),
                                        summary.endTime(),
                                        summary.location(),
                                        summary.cancelled()));
    }
}
