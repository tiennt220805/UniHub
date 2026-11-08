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
    public static final String BROCHURE_UPLOAD_ISSUED = "BROCHURE_UPLOAD_ISSUED";
    public static final String BROCHURE_UPLOAD_CONFIRMED = "BROCHURE_UPLOAD_CONFIRMED";
    public static final String COVER_IMAGE_UPLOAD_ISSUED = "COVER_IMAGE_UPLOAD_ISSUED";
    public static final String COVER_IMAGE_UPLOAD_CONFIRMED = "COVER_IMAGE_UPLOAD_CONFIRMED";
    public static final String COVER_IMAGE_UPLOAD_REJECTED = "COVER_IMAGE_UPLOAD_REJECTED";

    public static final String REGISTRATION_CREATED = "REGISTRATION_CREATED";
    public static final String REGISTRATION_CONFIRMED = "REGISTRATION_CONFIRMED";
    public static final String REGISTRATION_EXPIRED = "REGISTRATION_EXPIRED";

    public static final String PAYMENT_INITIATED = "PAYMENT_INITIATED";
    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_CANCELLED = "PAYMENT_CANCELLED";
    public static final String PAYMENT_REFUND_REQUIRED = "PAYMENT_REFUND_REQUIRED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";

    public static final String CHECKIN_SCANS_SYNCED = "CHECKIN_SCANS_SYNCED";

    private AuditActions() {}
}
