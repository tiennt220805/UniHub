package com.unihub.workshop.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** packages/api-contract {@code CoverImageUploadConfirmResponse} (workshop.md §7 confirmation). */
@Schema(
        types = {"object"},
        requiredProperties = {"coverImageUrl"})
public record CoverImageUploadConfirmResponse(String coverImageUrl) {}
