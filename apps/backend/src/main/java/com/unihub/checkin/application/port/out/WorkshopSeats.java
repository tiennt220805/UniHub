package com.unihub.checkin.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Optional;

/**
 * What the report needs from Workshop (Phase 5 checklist D46): its seat columns, read from a
 * Replica and never through the Redis cache. Owned by Check-in; the adapter answers from Workshop's
 * {@code api}.
 */
public interface WorkshopSeats {

    /** Empty when the workshop does not exist; any status counts as existing. */
    Optional<ReportedSeats> seatsOf(WorkshopId workshopId);
}
