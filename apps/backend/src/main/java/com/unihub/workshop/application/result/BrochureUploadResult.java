package com.unihub.workshop.application.result;

import java.net.URI;
import java.time.Instant;

/** A presigned PUT: one key, one object, until {@code expiresAt} (workshop.md §7). */
public record BrochureUploadResult(URI uploadUrl, Instant expiresAt) {}
