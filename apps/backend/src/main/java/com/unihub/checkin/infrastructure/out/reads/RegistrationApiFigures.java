package com.unihub.checkin.infrastructure.out.reads;

import com.unihub.checkin.application.port.out.RegistrationTotals;
import com.unihub.checkin.application.port.out.WorkshopRegistrationFigures;
import com.unihub.registration.api.WorkshopRegistrations;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link WorkshopRegistrationFigures} over {@code registration.api}: translation only. */
@Component
class RegistrationApiFigures implements WorkshopRegistrationFigures {
    private final WorkshopRegistrations registrations;

    RegistrationApiFigures(WorkshopRegistrations registrations) {
        this.registrations = registrations;
    }

    @Override
    public RegistrationTotals totalsOf(WorkshopId workshopId) {
        var counts = registrations.countsOf(workshopId);
        return new RegistrationTotals(counts.confirmed(), counts.reserved());
    }

    @Override
    public List<RegistrationId> idsOf(WorkshopId workshopId) {
        return registrations.idsOf(workshopId);
    }
}
