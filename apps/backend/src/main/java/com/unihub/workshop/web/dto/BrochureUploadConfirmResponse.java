package com.unihub.workshop.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** packages/api-contract {@code BrochureUploadConfirmResponse} (workshop.md §7 confirmation). */
@Schema(types = {"object"})
public record BrochureUploadConfirmResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasBrochure) {}
