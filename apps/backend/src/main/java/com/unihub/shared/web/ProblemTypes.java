package com.unihub.shared.web;

import java.net.URI;

/**
 * The {@code type} URIs a client may branch on (backend-coding-standards.md §17, Phase 2 checklist
 * D8). The set is a stable contract: a slug names a business condition, never an HTTP status, and
 * once used is never reused for another meaning. Added together with the use case that raises them;
 * everything else keeps {@code about:blank}.
 */
public final class ProblemTypes {

    private static final String PREFIX = "urn:unihub:problem:";

    public static final URI SOLD_OUT = URI.create(PREFIX + "sold-out");
    public static final URI ALREADY_REGISTERED = URI.create(PREFIX + "already-registered");
    public static final URI REGISTRATION_NOT_OPEN = URI.create(PREFIX + "registration-not-open");

    public static final URI REGISTRATION_NOT_RESERVED =
            URI.create(PREFIX + "registration-not-reserved");
    public static final URI PAYMENT_IN_PROGRESS = URI.create(PREFIX + "payment-in-progress");

    public static final URI IDEMPOTENCY_KEY_IN_USE = URI.create(PREFIX + "idempotency-key-in-use");
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(PREFIX + "idempotency-key-reused");

    /** workshop.md §7: the bucket cannot be reached; a retry of the same call is safe. */
    public static final URI STORAGE_UNAVAILABLE = URI.create(PREFIX + "storage-unavailable");

    /** workshop.md §7: confirming a brochure upload no URL was issued for, or that is confirmed. */
    public static final URI BROCHURE_UPLOAD_NOT_ISSUED =
            URI.create(PREFIX + "brochure-upload-not-issued");

    private ProblemTypes() {}
}
