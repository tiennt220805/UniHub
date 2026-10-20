package com.unihub.workshop.application.port.out;

/**
 * Counters the Phase 8 review of the shared {@code workshops.version} reads (observability.md §4).
 */
public interface WorkshopMetrics {

    /**
     * An organizer edit was refused by the version check ({@code
     * unihub_workshop_edit_conflicts_total}).
     */
    void editConflicted();
}
