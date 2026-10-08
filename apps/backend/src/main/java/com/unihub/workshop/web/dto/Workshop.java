package com.unihub.workshop.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * packages/api-contract {@code Workshop}. Shares its simple name with the domain aggregate by
 * design (§10): {@code web} cannot import {@code domain.model}, so the two never meet. The
 * {@code @Schema} attributes only describe the shape the contract fixes (required list, nullable
 * types, minimums) so contract-diff can compare it; the {@code isPaid}/{@code price} if/then/else
 * is added by {@code WorkshopOpenApiConfig}.
 */
@Schema(
        types = {"object"},
        requiredProperties = {
            "id",
            "title",
            "speakerName",
            "location",
            "startTime",
            "endTime",
            "capacity",
            "seatsTaken",
            "isPaid",
            "hasBrochure",
            "status",
            "version"
        })
public record Workshop(
        UUID id,
        String title,
        @Schema(types = {"string", "null"}) String description,
        @Schema(types = {"string", "null"}) String aiSummary,
        String speakerName,
        @Schema(types = {"string", "null"}) String speakerBio,
        String location,
        Instant startTime,
        Instant endTime,
        @Schema(minimum = "0") int capacity,
        @Schema(minimum = "0") int seatsTaken,
        @JsonProperty("isPaid") boolean isPaid,
        @Schema(
                        types = {"number", "null"},
                        format = "double")
                BigDecimal price,
        boolean hasBrochure,
        WorkshopStatus status,
        int version,
        Instant createdAt,
        Instant updatedAt) {}
