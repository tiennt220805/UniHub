package com.unihub.workshop.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// type=object / requiredProperties: springdoc's OpenAPI 3.1 output omits `type: object` on records
// and marks no property required, while schemas/workshop.yaml requires the list below. The
// isPaid/price if/then/else is added by WorkshopOpenApiConfig, as for Workshop.
@Schema(
        types = {"object"},
        requiredProperties = {
            "id",
            "title",
            "location",
            "startTime",
            "status",
            "speakerName",
            "endTime",
            "capacity",
            "seatsTaken",
            "isPaid"
        })
public record WorkshopSummary(
        UUID id,
        String title,
        String location,
        Instant startTime,
        WorkshopStatus status,
        @Schema(maxLength = 255) String speakerName,
        Instant endTime,
        @Schema(minimum = "0") int capacity,
        @Schema(minimum = "0") int seatsTaken,
        @JsonProperty("isPaid") boolean isPaid,
        @Schema(
                        types = {"number", "null"},
                        format = "double")
                BigDecimal price,
        @Schema(types = {"string", "null"}) String coverImageUrl) {}
