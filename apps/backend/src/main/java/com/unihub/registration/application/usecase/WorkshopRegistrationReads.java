package com.unihub.registration.application.usecase;

import com.unihub.registration.api.RegistrationCounts;
import com.unihub.registration.api.WorkshopRegistrations;
import com.unihub.registration.application.port.out.RegistrationQueries;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Replica, on purpose: a report tolerates a few seconds of lag and must not load the Master. */
@Service
class WorkshopRegistrationReads implements WorkshopRegistrations {
    private final RegistrationQueries queries;

    WorkshopRegistrationReads(RegistrationQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public RegistrationCounts countsOf(WorkshopId workshopId) {
        return queries.countsOf(workshopId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegistrationId> idsOf(WorkshopId workshopId) {
        return queries.idsOf(workshopId);
    }
}
