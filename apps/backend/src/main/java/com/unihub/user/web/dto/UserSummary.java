package com.unihub.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** packages/api-contract {@code UserSummary}: display data for the client, never authorization. */
@Schema(
        types = {"object"},
        requiredProperties = {"id", "fullName", "email", "roles"})
public record UserSummary(
        UUID id, String fullName, @Schema(format = "email") String email, List<String> roles) {}
