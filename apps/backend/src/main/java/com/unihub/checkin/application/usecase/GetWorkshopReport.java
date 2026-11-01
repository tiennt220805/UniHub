package com.unihub.checkin.application.usecase;

import com.unihub.checkin.application.port.in.GetWorkshopReportUseCase;
import com.unihub.checkin.application.port.out.AttendanceQueries;
import com.unihub.checkin.application.port.out.WorkshopRegistrationFigures;
import com.unihub.checkin.application.port.out.WorkshopSeats;
import com.unihub.checkin.application.result.WorkshopReportResult;
import com.unihub.checkin.domain.exception.ReportedWorkshopNotFoundException;
import com.unihub.shared.domain.id.WorkshopId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The report of one workshop (checkin.md §10; Phase 5 checklist D46). One read-only transaction, so
 * it is routed to the Replica and the three reads share it: a report tolerates a few seconds of
 * lag, which is what "real time" means here (D62). Nothing is cached, so nothing here depends on
 * Redis.
 */
@Service
class GetWorkshopReport implements GetWorkshopReportUseCase {
    private final WorkshopSeats seats;
    private final WorkshopRegistrationFigures registrations;
    private final AttendanceQueries attendances;

    GetWorkshopReport(
            WorkshopSeats seats,
            WorkshopRegistrationFigures registrations,
            AttendanceQueries attendances) {
        this.seats = seats;
        this.registrations = registrations;
        this.attendances = attendances;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkshopReportResult report(WorkshopId workshopId) {
        var figures =
                seats.seatsOf(workshopId)
                        .orElseThrow(() -> new ReportedWorkshopNotFoundException(workshopId));
        var totals = registrations.totalsOf(workshopId);
        var checkedIn = attendances.countFor(registrations.idsOf(workshopId));
        // A rate over nothing is undefined; 0 would claim nobody showed up of nobody. It is not
        // clamped: an attendance outlives the cancellation of its registration, so it can top 1.
        Double rate =
                totals.confirmed() == 0 ? null : (double) checkedIn / (double) totals.confirmed();
        return new WorkshopReportResult(
                workshopId.value(),
                figures.capacity(),
                figures.seatsTaken(),
                totals.confirmed(),
                totals.reserved(),
                checkedIn,
                rate);
    }
}
