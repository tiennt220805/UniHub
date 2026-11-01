package com.unihub.checkin.application.port.out;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;

/**
 * What the report needs from Registration (Phase 5 checklist D46): counts per status and the ids of
 * the workshop's registrations. Check-in cannot join {@code registrations} (a module reads
 * another's tables only through its {@code api}), and {@code attendances} carries no workshop id,
 * so attendances are counted and listed by these ids. Owned by Check-in; the adapter answers from
 * Registration's {@code api}.
 */
public interface WorkshopRegistrationFigures {

    RegistrationTotals totalsOf(WorkshopId workshopId);

    /** Every registration of the workshop in any status, cancelled and expired included. */
    List<RegistrationId> idsOf(WorkshopId workshopId);
}
