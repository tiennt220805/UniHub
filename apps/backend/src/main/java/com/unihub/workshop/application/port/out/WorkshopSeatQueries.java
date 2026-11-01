package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.SeatFigures;
import java.util.Optional;

/** Read model of a workshop's seat columns (backend-coding-standards.md §20). */
public interface WorkshopSeatQueries {

    Optional<SeatFigures> figuresOf(WorkshopId id);
}
