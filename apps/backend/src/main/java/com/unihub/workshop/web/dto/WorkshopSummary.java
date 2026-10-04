package com.unihub.workshop.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

// type=object / requiredProperties: springdoc's OpenAPI 3.1 output omits `type: object` on records
// and
// marks no property required, while schemas/workshop.yaml requires all five.
@Schema(
        types = {"object"},
        requiredProperties = {"id", "title", "location", "startTime", "status"})
public record WorkshopSummary(
        UUID id, String title, String location, Instant startTime, WorkshopStatus status) {}
