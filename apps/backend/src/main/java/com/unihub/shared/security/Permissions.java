package com.unihub.shared.security;

/**
 * Permission codes; each value is a {@code permissions.code} row seeded by V2 (database-schema.md
 * §2.3, user.md). Add a constant here <em>and</em> a {@code permissions} row via migration, never
 * one without the other. Endpoints guard with {@code hasAuthority(...)} on these, never a role name
 * (backend-coding-standards.md §11).
 */
public final class Permissions {

    public static final String WORKSHOP_CREATE = "WORKSHOP_CREATE";
    public static final String WORKSHOP_EDIT = "WORKSHOP_EDIT";
    public static final String CHECKIN_SCAN = "CHECKIN_SCAN";
    public static final String REPORT_VIEW = "REPORT_VIEW";

    /** Create a registration and pay for one's own (V4; Phase 2 checklist D7). */
    public static final String REGISTRATION_CREATE = "REGISTRATION_CREATE";

    private Permissions() {}
}
