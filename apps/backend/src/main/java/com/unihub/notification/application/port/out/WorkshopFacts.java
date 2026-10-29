package com.unihub.notification.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** A workshop as of now: enough to word a message and to recognise a stale reschedule. */
public record WorkshopFacts(
        UUID workshopId,
        String title,
        Instant startTime,
        Instant endTime,
        String location,
        boolean cancelled) {}
