package com.unihub.checkin.web.controller;

import com.unihub.checkin.application.port.in.GetVerificationKeyUseCase;
import com.unihub.checkin.application.port.in.SyncScanBatchUseCase;
import com.unihub.checkin.web.dto.SyncScanBatchRequest;
import com.unihub.checkin.web.dto.SyncScanBatchResponse;
import com.unihub.checkin.web.dto.VerificationKeyResponse;
import com.unihub.checkin.web.mapper.CheckInWebMapper;
import com.unihub.shared.security.Permissions;
import com.unihub.shared.web.ApiProblems;
import com.unihub.shared.web.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getVerificationKey} and {@code syncScanBatch} (packages/api-contract/paths/checkin.yaml).
 * The controller turns HTTP into a Command and the Result back into HTTP. There is no {@code
 * Idempotency-Key}: a scan is identified by its own client-generated id, which makes a resent batch
 * safe (Phase 5 checklist D54). A scan the device rejected can carry any {@code ticketCode} without
 * failing the batch (D50); see {@link CheckInWebMapper}.
 *
 * <p>The annotations on the method only DESCRIBE the contract for contract-diff.
 */
@RestController
@RequestMapping("/api/v1/checkin")
class CheckInController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final SyncScanBatchUseCase syncScanBatch;
    private final GetVerificationKeyUseCase verificationKey;

    CheckInController(
            SyncScanBatchUseCase syncScanBatch, GetVerificationKeyUseCase verificationKey) {
        this.syncScanBatch = syncScanBatch;
        this.verificationKey = verificationKey;
    }

    /**
     * The public half of the ticket signing key, for a device to verify tickets offline (M-48).
     * Gated on {@code CHECKIN_SCAN} like the sync, though the key is not confidential: every check
     * is a permission check (architecture.md §5.1).
     */
    @Operation(
            operationId = "getVerificationKey",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.CHECKIN_SCAN)),
            tags = {"Check-in"},
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
                            schema = @Schema(implementation = VerificationKeyResponse.class)))
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @GetMapping(path = "/verification-key", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).CHECKIN_SCAN)")
    ResponseEntity<VerificationKeyResponse> verificationKey() {
        return ResponseEntity.ok(CheckInWebMapper.toResponse(verificationKey.current()));
    }

    @Operation(
            operationId = "syncScanBatch",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.CHECKIN_SCAN)),
            tags = {"Check-in"},
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
                            schema = @Schema(implementation = SyncScanBatchResponse.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @PostMapping(
            path = "/sync",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).CHECKIN_SCAN)")
    ResponseEntity<SyncScanBatchResponse> sync(
            @Valid @RequestBody SyncScanBatchRequest request, @AuthenticationPrincipal Jwt jwt) {
        var result =
                syncScanBatch.sync(CheckInWebMapper.toCommand(request, CurrentActor.from(jwt)));
        return ResponseEntity.ok(CheckInWebMapper.toResponse(result));
    }
}
