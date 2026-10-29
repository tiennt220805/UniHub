package com.unihub.workshop.api;

import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/**
 * What a message about a workshop needs: its name, when and where, and whether it was cancelled.
 * Nothing else of the aggregate leaves Workshop.
 *
 * @param cancelled the workshop is {@code CANCELLED}
 */
public record WorkshopSummary(
        WorkshopId id,
        String title,
        Instant startTime,
        Instant endTime,
        String location,
        boolean cancelled) {}
