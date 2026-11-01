package com.unihub.checkin.application.result;

import java.util.UUID;

/**
 * The summary of one workshop. {@code checkInRate} is check-ins divided by {@code CONFIRMED}
 * registrations, {@code null} when there are none, and is not clamped: it can exceed 1 (checkin.md
 * §10).
 */
public record WorkshopReportResult(
        UUID workshopId,
        int capacity,
        int seatsTaken,
        long confirmedCount,
        long reservedCount,
        long checkedInCount,
        Double checkInRate) {}
