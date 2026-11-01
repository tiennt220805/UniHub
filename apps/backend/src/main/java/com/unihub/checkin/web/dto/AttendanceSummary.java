package com.unihub.checkin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** packages/api-contract {@code AttendanceSummary}. */
@Schema(
        types = {"object"},
        requiredProperties = {"registrationId", "checkedInAt"})
public record AttendanceSummary(UUID registrationId, Instant checkedInAt) {}
