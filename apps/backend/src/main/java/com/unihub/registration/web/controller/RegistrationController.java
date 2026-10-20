package com.unihub.registration.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.web.dto.CreateRegistrationRequest;
import com.unihub.registration.web.dto.Registration;
import com.unihub.registration.web.mapper.RegistrationWebMapper;
import com.unihub.shared.security.Permissions;
import com.unihub.shared.web.ApiProblems;
import com.unihub.shared.web.CurrentActor;
import com.unihub.shared.web.ProblemTypes;
import com.unihub.shared.web.Problems;
import com.unihub.shared.web.RequestFingerprint;
import com.unihub.shared.web.dto.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createRegistration} (packages/api-contract/paths/registration.yaml). The controller only
 * turns HTTP into a Command and a Result back into HTTP: the business outcomes (sold out, already
 * registered, not open) are Result variants mapped to a 409 with their own problem {@code type}
 * (D8); the transient failures (lock busy, claim retries exhausted) and a missing workshop are
 * exceptions that {@code GlobalProblemHandler} turns into 503 + {@code Retry-After} and 404.
 *
 * <p>The {@code Idempotency-Key} header is required and validated as a UUID; with the hash of the
 * body (canonical JSON) it goes into the Command, and the use case records and replays the Result.
 * Replaying is deterministic because this mapping is: a Result always becomes the same status,
 * {@code type} and body. A key reused with another body is 422, one still being processed is 409
 * (both exceptions with their own {@code type}).
 *
 * <p>The annotations on the method only DESCRIBE the contract for contract-diff. 429 (rate limit)
 * is documented only: no rate limiter exists yet.
 */
@RestController
@RequestMapping("/api/v1/registrations")
class RegistrationController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final ReserveSeatUseCase reserveSeat;
    private final ObjectMapper json;

    RegistrationController(ReserveSeatUseCase reserveSeat, ObjectMapper json) {
        this.reserveSeat = reserveSeat;
        this.json = json;
    }

    @Operation(
            operationId = "createRegistration",
            extensions = {
                @Extension(
                        properties =
                                @ExtensionProperty(
                                        name = "x-required-permission",
                                        value = Permissions.REGISTRATION_CREATE)),
                @Extension(
                        properties =
                                @ExtensionProperty(
                                        name = "x-idempotent",
                                        value = "true",
                                        parseValue = true))
            },
            tags = {"Registration"},
            parameters = {
                @Parameter(
                        name = "X-Correlation-Id",
                        in = ParameterIn.HEADER,
                        required = false,
                        schema =
                                @Schema(
                                        types = {"string"},
                                        pattern = CORRELATION_ID_PATTERN))
            })
    @ApiResponse(
            responseCode = "201",
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
                            schema = @Schema(implementation = Registration.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @ApiProblems.NotFound
    @ApiProblems.Conflict
    @ApiResponse(
            responseCode = "422",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "429",
            headers = @Header(name = "Retry-After", schema = @Schema(types = {"integer"})),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "503",
            headers = @Header(name = "Retry-After", schema = @Schema(types = {"integer"})),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemSchema.class)))
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).REGISTRATION_CREATE)")
    ResponseEntity<?> create(
            @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            required = true,
                            schema =
                                    @Schema(
                                            types = {"string"},
                                            format = "uuid"))
                    @RequestHeader("Idempotency-Key")
                    UUID idempotencyKey,
            @Valid @RequestBody CreateRegistrationRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest http) {
        var command =
                RegistrationWebMapper.toCommand(
                        request,
                        idempotencyKey.toString(),
                        RequestFingerprint.of(json, request),
                        CurrentActor.from(jwt));
        return switch (reserveSeat.reserve(command)) {
            case ReserveSeatResult.Reserved reserved ->
                    ResponseEntity.status(HttpStatus.CREATED)
                            .body(RegistrationWebMapper.toResponse(command, reserved));
            case ReserveSeatResult.Confirmed confirmed ->
                    ResponseEntity.status(HttpStatus.CREATED)
                            .body(RegistrationWebMapper.toResponse(command, confirmed));
            case ReserveSeatResult.SoldOut soldOut ->
                    Problems.conflict(http, ProblemTypes.SOLD_OUT, "Workshop is sold out.");
            case ReserveSeatResult.AlreadyRegistered already ->
                    Problems.conflict(
                            http,
                            ProblemTypes.ALREADY_REGISTERED,
                            "You already hold a registration for this workshop.");
            case ReserveSeatResult.NotOpenForRegistration closed ->
                    Problems.conflict(
                            http,
                            ProblemTypes.REGISTRATION_NOT_OPEN,
                            "Workshop is not open for registration.");
        };
    }
}
