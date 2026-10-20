package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.Optional;

/**
 * The only writer of {@code workshops.seats_taken} (Phase 2 checklist D3). The version is the
 * optimistic-concurrency arbiter shared with organizer edits; the capacity guard and the table's
 * CHECK are the layers behind it.
 */
public interface SeatCounter {

    /** Current seat columns, read on the Master inside the caller's transaction. */
    Optional<SeatSnapshot> read(WorkshopId workshopId);

    /**
     * {@code UPDATE workshops SET seats_taken = seats_taken + 1, version = version + 1 WHERE id = ?
     * AND version = ? AND seats_taken < capacity}.
     *
     * @return whether exactly one row was updated; {@code false} is ambiguous (full, or the version
     *     moved) and the caller re-reads to tell which
     */
    boolean tryClaim(WorkshopId workshopId, Version expected);

    /**
     * {@code UPDATE workshops SET seats_taken = seats_taken - 1, version = version + 1 WHERE id = ?
     * AND version = ? AND seats_taken > 0}.
     *
     * @return whether exactly one row was updated
     */
    boolean tryRelease(WorkshopId workshopId, Version expected);
}
