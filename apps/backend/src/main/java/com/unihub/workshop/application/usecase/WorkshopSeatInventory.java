package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.SeatClaimOutcome;
import com.unihub.workshop.api.SeatInventory;
import com.unihub.workshop.application.port.out.SeatCounter;
import com.unihub.workshop.application.port.out.SeatSnapshot;
import com.unihub.workshop.application.port.out.WorkshopCache;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.WorkshopStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seat claim and release for Registration (backend-coding-standards.md §19.5). {@code MANDATORY}
 * turns a call without a surrounding transaction into an immediate error instead of a silent
 * separate transaction that would split the seat count from the registration row.
 *
 * <p>The claim checks status and fullness first, then lets the single conditional UPDATE decide:
 * the pre-checks only choose the answer, they do not guard the write. A refused UPDATE is
 * re-examined — still room and still open means another writer bumped the version.
 */
@Service
class WorkshopSeatInventory implements SeatInventory {
    private static final String AGGREGATE = "WORKSHOP";

    private final SeatCounter seats;
    private final WorkshopCache cache;

    WorkshopSeatInventory(SeatCounter seats, WorkshopCache cache) {
        this.seats = seats;
        this.cache = cache;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SeatClaimOutcome claim(WorkshopId workshopId) {
        var snapshot = read(workshopId);
        var refusal = refusalOf(snapshot);
        if (refusal != null) {
            return refusal;
        }
        if (seats.tryClaim(workshopId, snapshot.version())) {
            cache.invalidateSeats(workshopId);
            return new SeatClaimOutcome.Claimed(snapshot.paid());
        }
        // Zero rows: either the last seat went in between, or the version moved (an organizer edit
        // or another claim). Only the first is a decision; the second is a retry for the caller.
        var current = read(workshopId);
        var nowRefused = refusalOf(current);
        if (nowRefused != null) {
            return nowRefused;
        }
        throw new OptimisticConflictException(AGGREGATE, workshopId.value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(WorkshopId workshopId) {
        var snapshot = read(workshopId);
        if (snapshot.seatsTaken() == 0) {
            throw new IllegalStateException(
                    "Workshop " + workshopId.value() + " has no taken seat to release");
        }
        if (!seats.tryRelease(workshopId, snapshot.version())) {
            throw new OptimisticConflictException(AGGREGATE, workshopId.value());
        }
        cache.invalidateSeats(workshopId);
    }

    private SeatSnapshot read(WorkshopId workshopId) {
        return seats.read(workshopId).orElseThrow(() -> new WorkshopNotFoundException(workshopId));
    }

    private static SeatClaimOutcome refusalOf(SeatSnapshot snapshot) {
        if (snapshot.status() != WorkshopStatus.PUBLISHED) {
            return new SeatClaimOutcome.NotOpenForRegistration();
        }
        return snapshot.isFull() ? new SeatClaimOutcome.SoldOut() : null;
    }
}
