package com.unihub.registration.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * packages/api-contract {@code CreateRegistrationRequest}. No {@code ticketType}: the server
 * derives it from the workshop's {@code isPaid}, a client cannot ask for the other kind.
 */
@Schema(
        types = {"object"},
        requiredProperties = {"workshopId"})
public record CreateRegistrationRequest(@NotNull UUID workshopId) {}
