package com.unihub.registration.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * packages/api-contract {@code RegistrationSummary}: the list projection. It deliberately has no
 * {@code qrPayload}: a list is not how the e-ticket is delivered.
 */
@Schema(
        types = {"object"},
        requiredProperties = {"id", "workshopId", "userId", "ticketType", "status", "createdAt"})
public record RegistrationSummary(
        UUID id,
        UUID workshopId,
        UUID userId,
        TicketType ticketType,
        RegistrationStatus status,
        Instant createdAt) {}
