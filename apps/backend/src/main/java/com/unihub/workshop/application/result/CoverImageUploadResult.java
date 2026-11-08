package com.unihub.workshop.application.result;

import java.net.URI;
import java.time.Instant;

/** A presigned PUT for one cover image: one key, one object, until {@code expiresAt}. */
public record CoverImageUploadResult(URI uploadUrl, Instant expiresAt) {}
