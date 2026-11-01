package com.unihub.registration.api;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;

/**
 * Registration's answer to "what does this workshop's registration side look like?" for Check-in's
 * report (Phase 5 checklist D46; backend-coding-standards.md §7, interaction type 1). Read from a
 * Replica: a report tolerates a few seconds of lag.
 */
public interface WorkshopRegistrations {

    /** The counts per status, computed by the database. Zero for a workshop with none. */
    RegistrationCounts countsOf(WorkshopId workshopId);

    /**
     * The id of <em>every</em> registration of the workshop, in any status. All of them, not only
     * those holding a seat: Check-in counts attendances by these ids, and an attendance already
     * recorded stays when its registration is cancelled later (checkin.md §7), so the cancelled
     * ones must be here or the report would under-count real check-ins.
     */
    List<RegistrationId> idsOf(WorkshopId workshopId);
}
