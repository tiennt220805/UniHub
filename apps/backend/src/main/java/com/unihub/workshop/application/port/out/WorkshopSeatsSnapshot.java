package com.unihub.workshop.application.port.out;

import com.unihub.workshop.domain.model.Workshop;
import java.time.Instant;
import java.util.UUID;

/**
 * The volatile part of a workshop, as cached for 3s (workshop.md §2): what a seat claim or a status
 * change moves. {@code version} and {@code updatedAt} are here, not in the 300s snapshot, because
 * every seat claim bumps them. A value for <em>display</em> only — a seat-reservation decision
 * never reads it.
 */
public record WorkshopSeatsSnapshot(
        UUID id, int seatsTaken, int capacity, String status, long version, Instant updatedAt) {

    public static WorkshopSeatsSnapshot from(Workshop w) {
        return new WorkshopSeatsSnapshot(
                w.id().value(),
                w.seatsTaken(),
                w.capacity(),
                w.status().name(),
                w.version().value(),
                w.updatedAt());
    }
}
