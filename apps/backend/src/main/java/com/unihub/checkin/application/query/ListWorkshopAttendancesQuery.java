package com.unihub.checkin.application.query;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Objects;

/** One page of a workshop's attendances; {@code page} is zero-based. */
public record ListWorkshopAttendancesQuery(WorkshopId workshopId, int page, int size) {

    public ListWorkshopAttendancesQuery {
        Objects.requireNonNull(workshopId);
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size >= 1");
        }
    }
}
