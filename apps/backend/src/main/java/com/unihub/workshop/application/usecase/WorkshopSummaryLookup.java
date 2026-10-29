package com.unihub.workshop.application.usecase;

import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.WorkshopSummaries;
import com.unihub.workshop.api.WorkshopSummary;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkshopSummaryLookup implements WorkshopSummaries {
    private final WorkshopRepository workshops;

    WorkshopSummaryLookup(WorkshopRepository workshops) {
        this.workshops = workshops;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public Optional<WorkshopSummary> find(WorkshopId id) {
        return workshops
                .findById(id)
                .map(
                        workshop ->
                                new WorkshopSummary(
                                        workshop.id(),
                                        workshop.title(),
                                        workshop.startTime(),
                                        workshop.endTime(),
                                        workshop.location(),
                                        workshop.status() == WorkshopStatus.CANCELLED));
    }
}
