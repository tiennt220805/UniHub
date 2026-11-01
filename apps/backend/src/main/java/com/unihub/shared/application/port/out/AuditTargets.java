package com.unihub.shared.application.port.out;

/**
 * Values of {@code audit_events.target_type}. Added together with the use case that records them.
 */
public final class AuditTargets {

    public static final String WORKSHOP = "WORKSHOP";

    public static final String REGISTRATION = "REGISTRATION";

    public static final String PAYMENT = "PAYMENT";

    /** A sync batch has no row of its own: the entry carries a fresh id. */
    public static final String CHECK_IN_BATCH = "CHECK_IN_BATCH";

    private AuditTargets() {}
}
