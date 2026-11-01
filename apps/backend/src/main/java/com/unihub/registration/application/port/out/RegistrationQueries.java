package com.unihub.registration.application.port.out;

import com.unihub.registration.api.RegistrationCounts;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;

/** Read model for a workshop's report (backend-coding-standards.md §20). */
public interface RegistrationQueries {

    RegistrationCounts countsOf(WorkshopId workshopId);

    /** Every registration of the workshop, whatever its status, in id order. */
    List<RegistrationId> idsOf(WorkshopId workshopId);
}
