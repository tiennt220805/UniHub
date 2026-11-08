package com.unihub.workshop.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;

/** packages/api-contract {@code CoverImageUploadUrlResponse} (workshop.md §7 issuance). */
@Schema(types = {"object"})
public record CoverImageUploadUrlResponse(
        @Schema(
                        types = {"string"},
                        format = "uri",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                URI uploadUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {}
