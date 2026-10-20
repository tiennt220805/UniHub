package com.unihub.shared.application.port.out;

/**
 * Values of {@code audit_events.action} (database-schema.md §8.3). Added together with the use case
 * that records them.
 */
public final class AuditActions {

    public static final String WORKSHOP_CREATED = "WORKSHOP_CREATED";
    public static final String WORKSHOP_PUBLISHED = "WORKSHOP_PUBLISHED";
    public static final String WORKSHOP_UPDATED = "WORKSHOP_UPDATED";
    public static final String WORKSHOP_RESCHEDULED = "WORKSHOP_RESCHEDULED";
    public static final String WORKSHOP_CANCELLED = "WORKSHOP_CANCELLED";

    private AuditActions() {}
}
