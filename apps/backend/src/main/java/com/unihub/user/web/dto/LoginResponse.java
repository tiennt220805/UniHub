package com.unihub.user.web.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** packages/api-contract {@code LoginResponse}. */
@Schema(
        types = {"object"},
        requiredProperties = {"accessToken", "expiresIn", "user", "permissions"})
public record LoginResponse(
        String accessToken,
        int expiresIn,
        UserSummary user,
        @ArraySchema(
                        uniqueItems = true,
                        schema =
                                @Schema(
                                        types = {"string"},
                                        maxLength = 100))
                List<String> permissions) {

    /** Keeps the bearer token out of logs. */
    @Override
    public String toString() {
        return "LoginResponse[accessToken=****, user=" + user + "]";
    }
}
