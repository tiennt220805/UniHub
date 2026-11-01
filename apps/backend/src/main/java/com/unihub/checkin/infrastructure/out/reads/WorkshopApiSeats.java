package com.unihub.checkin.infrastructure.out.reads;

import com.unihub.checkin.application.port.out.ReportedSeats;
import com.unihub.checkin.application.port.out.WorkshopSeats;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.WorkshopSeatFigures;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link WorkshopSeats} over {@code workshop.api}: translation only. */
@Component
class WorkshopApiSeats implements WorkshopSeats {
    private final WorkshopSeatFigures workshops;

    WorkshopApiSeats(WorkshopSeatFigures workshops) {
        this.workshops = workshops;
    }

    @Override
    public Optional<ReportedSeats> seatsOf(WorkshopId workshopId) {
        return workshops
                .find(workshopId)
                .map(figures -> new ReportedSeats(figures.capacity(), figures.seatsTaken()));
    }
}
