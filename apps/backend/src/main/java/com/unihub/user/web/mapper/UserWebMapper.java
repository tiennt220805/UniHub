package com.unihub.user.web.mapper;

import com.unihub.user.application.command.LoginCommand;
import com.unihub.user.application.result.LoginResult;
import com.unihub.user.web.dto.LoginRequest;
import com.unihub.user.web.dto.LoginResponse;
import com.unihub.user.web.dto.UserSummary;
import java.util.List;

public final class UserWebMapper {

    private UserWebMapper() {}

    public static LoginCommand toCommand(LoginRequest request) {
        return new LoginCommand(request.email(), request.password());
    }

    public static LoginResponse toResponse(LoginResult.Authenticated result) {
        return new LoginResponse(
                result.accessToken(),
                result.expiresInSeconds(),
                new UserSummary(
                        result.userId().value(), result.fullName(), result.email(), result.roles()),
                List.copyOf(result.permissions().stream().sorted().toList()));
    }
}
