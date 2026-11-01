package com.unihub.checkin.application.port.in;

import com.unihub.checkin.application.result.WorkshopReportResult;
import com.unihub.shared.domain.id.WorkshopId;

public interface GetWorkshopReportUseCase {

    /**
     * Registration counts and check-in rate of the workshop, counted by the database.
     *
     * @throws com.unihub.checkin.domain.exception.ReportedWorkshopNotFoundException when the
     *     workshop does not exist
     */
    WorkshopReportResult report(WorkshopId workshopId);
}
