package com.unihub.payment.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.payment.web.dto.CreatePaymentChargeRequest;
import com.unihub.payment.web.dto.Payment;
import com.unihub.payment.web.mapper.PaymentWebMapper;
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
 * {@code chargePayment} (packages/api-contract/paths/payment.yaml). Like {@code createRegistration}
 * the controller only turns HTTP into a Command and a Result back into HTTP. A processed charge is
 * always {@code 201} and carries the payment's real status ({@code SUCCEEDED}, {@code FAILED} or
 * {@code PENDING}, checklist D10); the two business rejections are Result variants mapped to a
 * {@code 409} with their own problem {@code type}; ownership ({@code 403}), a missing registration
 * ({@code 404}) and "nothing could have been charged" ({@code 503} + {@code Retry-After}) are
 * exceptions turned into problems by {@code GlobalProblemHandler}.
 *
 * <p>The mapping from a Result to a response is fixed, which is what makes a replay the identical
 * response ({@code PaymentResultHttpMappingTest}). The annotations only DESCRIBE the contract for
 * contract-diff.
 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final ChargePaymentUseCase chargePayment;
    private final ObjectMapper json;

    PaymentController(ChargePaymentUseCase chargePayment, ObjectMapper json) {
        this.chargePayment = chargePayment;
        this.json = json;
    }

    @Operation(
            operationId = "chargePayment",
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
            tags = {"Payment"},
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
                            schema = @Schema(implementation = Payment.class)))
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
            responseCode = "503",
            headers = @Header(name = "Retry-After", schema = @Schema(types = {"integer"})),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemSchema.class)))
    @PostMapping(
            path = "/charge",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).REGISTRATION_CREATE)")
    ResponseEntity<?> charge(
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
            @Valid @RequestBody CreatePaymentChargeRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest http) {
        var command =
                PaymentWebMapper.toCommand(
                        request,
                        idempotencyKey.toString(),
                        RequestFingerprint.of(json, request),
                        CurrentActor.from(jwt));
        return switch (chargePayment.charge(command)) {
            case ChargePaymentResult.Processed processed ->
                    ResponseEntity.status(HttpStatus.CREATED)
                            .body(PaymentWebMapper.toResponse(processed));
            case ChargePaymentResult.RegistrationNotReserved notReserved ->
                    Problems.conflict(
                            http,
                            ProblemTypes.REGISTRATION_NOT_RESERVED,
                            "The registration is not RESERVED, or its hold has ended.");
            case ChargePaymentResult.PaymentInProgress inProgress ->
                    Problems.conflict(
                            http,
                            ProblemTypes.PAYMENT_IN_PROGRESS,
                            "The registration already has a payment in progress or paid.");
        };
    }
}
