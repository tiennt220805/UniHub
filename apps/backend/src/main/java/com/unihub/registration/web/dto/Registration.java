package com.unihub.registration.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * packages/api-contract {@code Registration}. Shares its simple name with the domain aggregate by
 * design (§10): {@code web} cannot import {@code domain.model}. The {@code @Schema} attributes only
 * describe the shape the contract fixes; the {@code holdExpiresAt ⇔ RESERVED} if/then/else is added
 * by {@code RegistrationOpenApiConfig}. {@code ticketCode}/{@code qrPayload} stay {@code null}
 * until ticket issuance exists.
 */
@Schema(
        types = {"object"},
        requiredProperties = {"id", "workshopId", "userId", "ticketType", "status", "createdAt"})
public record Registration(
        UUID id,
        UUID workshopId,
        UUID userId,
        TicketType ticketType,
        RegistrationStatus status,
        @Schema(types = {"string", "null"}) Instant holdExpiresAt,
        @Schema(types = {"string", "null"}) String ticketCode,
        @Schema(types = {"string", "null"}) String qrPayload,
        Instant createdAt,
        Instant updatedAt) {}
