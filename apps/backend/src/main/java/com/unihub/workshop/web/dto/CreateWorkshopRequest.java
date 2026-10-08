package com.unihub.workshop.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * packages/api-contract {@code CreateWorkshopRequest}. The two cross-field rules the contract
 * cannot express in JSON Schema — {@code endTime > startTime}, and the {@code isPaid}/{@code price}
 * pairing — are checked here and are hidden from the generated schema, so the spec still matches
 * the contract. Everything else is a plain field constraint.
 */
@Schema(types = {"object"})
public record CreateWorkshopRequest(
        @NotBlank @Size(max = 255) String title,
        @Schema(types = {"string", "null"}) String description,
        @NotBlank @Size(max = 255) String speakerName,
        @Schema(types = {"string", "null"}) String speakerBio,
        @NotBlank @Size(max = 255) String location,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotNull @Min(0) Integer capacity,
        @NotNull Boolean isPaid,
        @Schema(
                        types = {"number", "null"},
                        format = "double")
                BigDecimal price) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "endTime must be after startTime")
    public boolean isEndAfterStart() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "price is required and positive when isPaid is true, absent otherwise")
    public boolean isPriceConsistentWithIsPaid() {
        if (isPaid == null) {
            return true; // reported as a missing field instead
        }
        return isPaid ? price != null && price.signum() > 0 : price == null;
    }
}
