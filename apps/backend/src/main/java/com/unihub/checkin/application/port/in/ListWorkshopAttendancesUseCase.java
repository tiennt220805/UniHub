package com.unihub.checkin.application.port.in;

import com.unihub.checkin.application.query.ListWorkshopAttendancesQuery;
import com.unihub.checkin.application.result.WorkshopAttendancesResult;

public interface ListWorkshopAttendancesUseCase {

    /**
     * One page of the workshop's attendances, earliest check-in first.
     *
     * @throws com.unihub.checkin.domain.exception.ReportedWorkshopNotFoundException when the
     *     workshop does not exist
     */
    WorkshopAttendancesResult list(ListWorkshopAttendancesQuery query);
}
