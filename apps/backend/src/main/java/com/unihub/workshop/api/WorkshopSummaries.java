package com.unihub.workshop.api;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Optional;

/**
 * Workshop's answer to "what is this workshop, as of now?" for Notification (Phase 4 checklist D35;
 * backend-coding-standards.md §7, interaction type 1). Read at the Master in its own transaction:
 * the current times and status are what let the consumer skip a stale reschedule, and they are only
 * trustworthy right after the write that produced the event.
 */
public interface WorkshopSummaries {

    /**
     * Empty when no such workshop exists. Any status is returned, drafts and cancelled included.
     */
    Optional<WorkshopSummary> find(WorkshopId id);
}
