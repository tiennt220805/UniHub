package com.unihub.checkin.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.checkin.application.port.out.AttendancePage;
import com.unihub.checkin.application.port.out.AttendanceQueries;
import com.unihub.checkin.application.port.out.RegistrationTotals;
import com.unihub.checkin.application.port.out.ReportedSeats;
import com.unihub.checkin.application.port.out.WorkshopRegistrationFigures;
import com.unihub.checkin.application.port.out.WorkshopSeats;
import com.unihub.checkin.domain.exception.ReportedWorkshopNotFoundException;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetWorkshopReportTest {
    private static final WorkshopId WORKSHOP = WorkshopId.newId();

    private final List<RegistrationId> registrationIds =
            List.of(RegistrationId.newId(), RegistrationId.newId(), RegistrationId.newId());
    private Optional<ReportedSeats> seats = Optional.of(new ReportedSeats(40, 4));
    private RegistrationTotals totals = new RegistrationTotals(3, 1);
    private long attendances = 2;
    private final List<Collection<RegistrationId>> countedIds = new ArrayList<>();

    private GetWorkshopReport useCase() {
        WorkshopSeats seatSource = id -> seats;
        WorkshopRegistrationFigures registrationSource =
                new WorkshopRegistrationFigures() {
                    @Override
                    public RegistrationTotals totalsOf(WorkshopId workshopId) {
                        return totals;
                    }

                    @Override
                    public List<RegistrationId> idsOf(WorkshopId workshopId) {
                        return registrationIds;
                    }
                };
        AttendanceQueries attendanceSource =
                new AttendanceQueries() {
                    @Override
                    public long countFor(Collection<RegistrationId> registrations) {
                        countedIds.add(registrations);
                        return attendances;
                    }

                    @Override
                    public AttendancePage pageFor(
                            Collection<RegistrationId> registrations, int page, int size) {
                        throw new AssertionError("a report never pages");
                    }
                };
        return new GetWorkshopReport(seatSource, registrationSource, attendanceSource);
    }

    @Test
    @DisplayName("3 confirmed, 1 reserved, 2 checked in: the figures as they are and a rate of 2/3")
    void figuresAndRate() {
        var report = useCase().report(WORKSHOP);

        assertThat(report.workshopId()).isEqualTo(WORKSHOP.value());
        assertThat(report.capacity()).isEqualTo(40);
        assertThat(report.seatsTaken()).isEqualTo(4);
        assertThat(report.confirmedCount()).isEqualTo(3);
        assertThat(report.reservedCount()).isEqualTo(1);
        assertThat(report.checkedInCount()).isEqualTo(2);
        assertThat(report.checkInRate()).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("attendances are counted over the ids of every registration the workshop has")
    void attendancesAreCountedByEveryRegistrationId() {
        useCase().report(WORKSHOP);

        assertThat(countedIds).hasSize(1);
        assertThat(countedIds.get(0)).containsExactlyElementsOf(registrationIds);
    }

    @Test
    @DisplayName("no confirmed registration: the rate is null, never 0")
    void noConfirmed_rateIsNull() {
        totals = new RegistrationTotals(0, 2);
        attendances = 0;

        assertThat(useCase().report(WORKSHOP).checkInRate()).isNull();
    }

    @Test
    @DisplayName("more check-ins than confirmed registrations: the rate is above 1 and unclamped")
    void moreCheckInsThanConfirmed_isNotClamped() {
        totals = new RegistrationTotals(1, 0);
        attendances = 2;

        assertThat(useCase().report(WORKSHOP).checkInRate()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a workshop with no registrations is a report of zeros, not a 404")
    void noRegistrations_isZeros() {
        totals = new RegistrationTotals(0, 0);
        attendances = 0;

        var report = useCase().report(WORKSHOP);

        assertThat(report.confirmedCount()).isZero();
        assertThat(report.reservedCount()).isZero();
        assertThat(report.checkedInCount()).isZero();
        assertThat(report.checkInRate()).isNull();
    }

    @Test
    @DisplayName("an unknown workshop is not found")
    void unknownWorkshop_isNotFound() {
        seats = Optional.empty();

        assertThatThrownBy(() -> useCase().report(WORKSHOP))
                .isInstanceOf(ReportedWorkshopNotFoundException.class);
    }
}
