package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.Version;
import com.unihub.workshop.domain.model.WorkshopStatus;

/** The columns a seat decision reads, taken at the Master in the caller's transaction. */
public record SeatSnapshot(
        WorkshopStatus status, boolean paid, int seatsTaken, int capacity, Version version) {

    public boolean isFull() {
        return seatsTaken >= capacity;
    }
}
