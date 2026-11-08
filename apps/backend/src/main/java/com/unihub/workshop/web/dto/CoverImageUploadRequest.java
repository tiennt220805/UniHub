package com.unihub.workshop.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * packages/api-contract {@code requestCoverImageUploadUrl} request body: a raster type and the
 * exact size of the file (Phase 8, D122). The contract's {@code enum} and bounds are what the
 * generated schema shows; the type check is the hidden assertion, as for the brochure.
 */
@Schema(types = {"object"})
public record CoverImageUploadRequest(
        @NotNull
                @Schema(
                        types = {"string"},
                        allowableValues = {"image/jpeg", "image/png", "image/webp"})
                String contentType,
        @NotNull @Min(1) @Max(2_097_152) @Schema(types = {"integer"}) Integer contentLength) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "contentType must be image/jpeg, image/png or image/webp")
    public boolean isRaster() {
        return contentType == null
                || "image/jpeg".equals(contentType)
                || "image/png".equals(contentType)
                || "image/webp".equals(contentType);
    }
}
