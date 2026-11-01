package com.unihub.checkin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * packages/api-contract {@code WorkshopReport}. The counts are {@code int} so the generated spec
 * says plain {@code integer} as the contract does; {@code checkInRate} is a number or null (see
 * {@code CheckInOpenApiConfig}).
 */
@Schema(
        types = {"object"},
        requiredProperties = {
            "workshopId",
            "capacity",
            "seatsTaken",
            "confirmedCount",
            "reservedCount",
            "checkedInCount",
            "checkInRate"
        })
public record WorkshopReport(
        UUID workshopId,
        @Schema(minimum = "1") int capacity,
        @Schema(minimum = "0") int seatsTaken,
        @Schema(minimum = "0") int confirmedCount,
        @Schema(minimum = "0") int reservedCount,
        @Schema(minimum = "0") int checkedInCount,
        Double checkInRate) {}
