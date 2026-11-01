package com.unihub.checkin.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.checkin.application.port.out.AttendanceEntry;
import com.unihub.checkin.application.port.out.AttendancePage;
import com.unihub.checkin.application.port.out.AttendanceQueries;
import com.unihub.checkin.application.port.out.RegistrationTotals;
import com.unihub.checkin.application.port.out.ReportedSeats;
import com.unihub.checkin.application.port.out.WorkshopRegistrationFigures;
import com.unihub.checkin.application.query.ListWorkshopAttendancesQuery;
import com.unihub.checkin.domain.exception.ReportedWorkshopNotFoundException;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ListWorkshopAttendancesTest {
    private static final WorkshopId WORKSHOP = WorkshopId.newId();

    private final List<RegistrationId> ids =
            List.of(RegistrationId.newId(), RegistrationId.newId());
    private Optional<ReportedSeats> seats = Optional.of(new ReportedSeats(10, 2));
    private int askedPage = -1;
    private int askedSize = -1;
    private Collection<RegistrationId> askedIds;

    private ListWorkshopAttendances useCase() {
        WorkshopRegistrationFigures registrations =
                new WorkshopRegistrationFigures() {
                    @Override
                    public RegistrationTotals totalsOf(WorkshopId workshopId) {
                        throw new AssertionError("a list needs no totals");
                    }

                    @Override
                    public List<RegistrationId> idsOf(WorkshopId workshopId) {
                        return ids;
                    }
                };
        AttendanceQueries attendances =
                new AttendanceQueries() {
                    @Override
                    public long countFor(Collection<RegistrationId> registrations) {
                        throw new AssertionError("a list counts inside pageFor");
                    }

                    @Override
                    public AttendancePage pageFor(
                            Collection<RegistrationId> registrations, int page, int size) {
                        askedIds = registrations;
                        askedPage = page;
                        askedSize = size;
                        return new AttendancePage(
                                List.of(new AttendanceEntry(ids.get(0), Instant.EPOCH)),
                                page,
                                size,
                                1,
                                1);
                    }
                };
        return new ListWorkshopAttendances(id -> seats, registrations, attendances);
    }

    @Test
    @DisplayName("the page asked for is read over the ids of every registration of the workshop")
    void readsTheAskedPageOverEveryRegistrationId() {
        var result = useCase().list(new ListWorkshopAttendancesQuery(WORKSHOP, 2, 50));

        assertThat(askedIds).containsExactlyElementsOf(ids);
        assertThat(askedPage).isEqualTo(2);
        assertThat(askedSize).isEqualTo(50);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).registrationId()).isEqualTo(ids.get(0).value());
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown workshop is not found, not an empty page")
    void unknownWorkshop_isNotFound() {
        seats = Optional.empty();

        assertThatThrownBy(() -> useCase().list(new ListWorkshopAttendancesQuery(WORKSHOP, 0, 20)))
                .isInstanceOf(ReportedWorkshopNotFoundException.class);
    }

    @Test
    @DisplayName("a negative page or a size below 1 cannot be asked for")
    void rejectsImpossiblePaging() {
        assertThatThrownBy(() -> new ListWorkshopAttendancesQuery(WORKSHOP, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListWorkshopAttendancesQuery(WORKSHOP, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
