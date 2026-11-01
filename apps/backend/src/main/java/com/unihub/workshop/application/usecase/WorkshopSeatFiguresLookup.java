package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.SeatFigures;
import com.unihub.workshop.api.WorkshopSeatFigures;
import com.unihub.workshop.application.port.out.WorkshopSeatQueries;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Replica, on purpose: a report tolerates a few seconds of lag and must not load the Master. */
@Service
class WorkshopSeatFiguresLookup implements WorkshopSeatFigures {
    private final WorkshopSeatQueries queries;

    WorkshopSeatFiguresLookup(WorkshopSeatQueries queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SeatFigures> find(WorkshopId id) {
        return queries.figuresOf(id);
    }
}
