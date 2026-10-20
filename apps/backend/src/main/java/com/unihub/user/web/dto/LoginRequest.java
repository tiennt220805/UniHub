package com.unihub.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** packages/api-contract {@code LoginRequest}. */
@Schema(
        types = {"object"},
        requiredProperties = {"email", "password"})
public record LoginRequest(
        @NotNull @Email @Size(max = 255) @Schema(format = "email") String email,
        @NotNull @Schema(format = "password") String password) {

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
