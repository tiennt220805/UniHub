package com.unihub.user.web.controller;

import com.unihub.shared.web.ApiProblems;
import com.unihub.shared.web.dto.ProblemSchema;
import com.unihub.user.application.port.in.LoginUseCase;
import com.unihub.user.application.result.LoginResult;
import com.unihub.user.web.dto.LoginRequest;
import com.unihub.user.web.dto.LoginResponse;
import com.unihub.user.web.mapper.UserWebMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// POST /api/v1/auth/login is public (packages/api-contract/paths/user.yaml: security: []);
// SecurityConfig permits this path. Every refusal is the same 401 body, whatever the reason
// (paths/user.yaml login, auth-and-identity.md §7).
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";
    private static final String REJECTED_DETAIL = "Invalid credentials.";

    private final LoginUseCase login;

    AuthController(LoginUseCase login) {
        this.login = login;
    }

    // 429 + Retry-After and the X-Correlation-Id response header are documented only, as on the
    // workshop endpoints: no rate limiter (INV-17, Phase 7) or correlation-ID filter exists yet.
    @Operation(
            operationId = "login",
            tags = {"User"})
    @SecurityRequirements
    @ApiResponse(
            responseCode = "200",
            headers =
                    @Header(
                            name = "X-Correlation-Id",
                            schema =
                                    @Schema(
                                            types = {"string"},
                                            pattern = CORRELATION_ID_PATTERN)),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LoginResponse.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiResponse(
            responseCode = "429",
            headers = @Header(name = "Retry-After", schema = @Schema(types = {"integer"})),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemSchema.class)))
    @PostMapping(
            path = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return switch (login.login(UserWebMapper.toCommand(request))) {
            case LoginResult.Authenticated ok -> ResponseEntity.ok(UserWebMapper.toResponse(ok));
            case LoginResult.Rejected rejected -> unauthorized();
        };
    }

    private static ResponseEntity<ProblemDetail> unauthorized() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, REJECTED_DETAIL);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
