package com.unihub.workshop.api;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Optional;

/**
 * Workshop's answer to "how many seats, and how many taken?" for Check-in's report (Phase 5
 * checklist D46; backend-coding-standards.md §7, interaction type 1). Read from a Replica, from the
 * table and never through the Redis catalog cache: a report must not depend on Redis (the outage
 * probe of resilience-testing.md §2.3), and "real time" here is a few seconds of replica lag.
 */
public interface WorkshopSeatFigures {

    /**
     * Empty when no such workshop exists. Any status is returned, drafts and cancelled included.
     */
    Optional<SeatFigures> find(WorkshopId id);
}
