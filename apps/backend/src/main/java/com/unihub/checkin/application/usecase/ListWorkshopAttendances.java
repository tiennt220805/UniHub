package com.unihub.checkin.application.usecase;

import com.unihub.checkin.application.port.in.ListWorkshopAttendancesUseCase;
import com.unihub.checkin.application.port.out.AttendanceQueries;
import com.unihub.checkin.application.port.out.WorkshopRegistrationFigures;
import com.unihub.checkin.application.port.out.WorkshopSeats;
import com.unihub.checkin.application.query.ListWorkshopAttendancesQuery;
import com.unihub.checkin.application.result.WorkshopAttendancesResult;
import com.unihub.checkin.domain.exception.ReportedWorkshopNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-registration view behind the report (checkin.md §10). Read from the Replica in one
 * transaction, like the report; the workshop must exist, so an unknown id is a 404 rather than an
 * empty page.
 */
@Service
class ListWorkshopAttendances implements ListWorkshopAttendancesUseCase {
    private final WorkshopSeats workshops;
    private final WorkshopRegistrationFigures registrations;
    private final AttendanceQueries attendances;

    ListWorkshopAttendances(
            WorkshopSeats workshops,
            WorkshopRegistrationFigures registrations,
            AttendanceQueries attendances) {
        this.workshops = workshops;
        this.registrations = registrations;
        this.attendances = attendances;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkshopAttendancesResult list(ListWorkshopAttendancesQuery query) {
        if (workshops.seatsOf(query.workshopId()).isEmpty()) {
            throw new ReportedWorkshopNotFoundException(query.workshopId());
        }
        return WorkshopAttendancesResult.of(
                attendances.pageFor(
                        registrations.idsOf(query.workshopId()), query.page(), query.size()));
    }
}
