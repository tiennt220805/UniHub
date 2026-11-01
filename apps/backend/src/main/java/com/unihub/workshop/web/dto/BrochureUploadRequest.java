package com.unihub.workshop.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * packages/api-contract {@code requestBrochureUploadUrl} request body: PDF only (workshop.md §6).
 * The contract's {@code enum} is what the generated schema shows; the check itself is the hidden
 * assertion, so no {@code pattern} appears in the spec that the contract does not have.
 */
@Schema(types = {"object"})
public record BrochureUploadRequest(
        @NotNull
                @Schema(
                        types = {"string"},
                        allowableValues = {"application/pdf"})
                String contentType) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "contentType must be application/pdf")
    public boolean isPdf() {
        return contentType == null || "application/pdf".equals(contentType);
    }
}
