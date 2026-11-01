package com.unihub.checkin.infrastructure.out.reads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.checkin.application.port.out.ReportedSeats;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.SeatFigures;
import com.unihub.workshop.api.WorkshopSeatFigures;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Translation only: Workshop's two seat columns become Check-in's, unchanged. */
class WorkshopApiSeatsTest {
    private final WorkshopSeatFigures workshops = mock(WorkshopSeatFigures.class);
    private final WorkshopApiSeats seats = new WorkshopApiSeats(workshops);

    @Test
    @DisplayName("capacity and seats taken cross the boundary as they are")
    void translates() {
        var id = WorkshopId.newId();
        when(workshops.find(id)).thenReturn(Optional.of(new SeatFigures(40, 7)));

        assertThat(seats.seatsOf(id)).contains(new ReportedSeats(40, 7));
    }

    @Test
    @DisplayName("a workshop Workshop does not know is empty")
    void unknown() {
        var id = WorkshopId.newId();
        when(workshops.find(id)).thenReturn(Optional.empty());

        assertThat(seats.seatsOf(id)).isEmpty();
    }
}
