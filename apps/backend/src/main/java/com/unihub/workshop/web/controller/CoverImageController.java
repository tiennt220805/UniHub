package com.unihub.workshop.web.controller;

import com.unihub.shared.web.ApiProblems;
import com.unihub.shared.web.CurrentActor;
import com.unihub.shared.web.dto.ProblemSchema;
import com.unihub.workshop.application.command.ConfirmCoverImageUploadCommand;
import com.unihub.workshop.application.command.RequestCoverImageUploadCommand;
import com.unihub.workshop.application.port.in.ConfirmCoverImageUploadUseCase;
import com.unihub.workshop.application.port.in.RequestCoverImageUploadUseCase;
import com.unihub.workshop.web.dto.CoverImageUploadConfirmResponse;
import com.unihub.workshop.web.dto.CoverImageUploadRequest;
import com.unihub.workshop.web.dto.CoverImageUploadUrlResponse;
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
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two HTTP steps of a cover image upload (workshop.md §7, Phase 8; packages/api-contract {@code
 * requestCoverImageUploadUrl}, {@code confirmCoverImageUpload}). The image's bytes never pass
 * through here: the browser PUTs them to the issued URL, and the edge serves them back. Either
 * permission satisfies the check, as the contract says; whether the caller may touch this
 * particular workshop is decided exactly as {@code UpdateWorkshop} decides it, which is not at all
 * beyond the permission (there is no per-workshop ownership in this system).
 *
 * <p>The annotations describe the contract so contract-diff can compare shapes. {@code
 * X-Correlation-Id} response header is written by {@code CorrelationIdFilter} on every endpoint;
 * the request carries no such parameter (a client's header is ignored).
 */
@RestController
@RequestMapping("/api/v1/workshops/{id}")
class CoverImageController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";
    private static final String EITHER_PERMISSION =
            "hasAnyAuthority(T(com.unihub.shared.security.Permissions).WORKSHOP_CREATE,"
                    + " T(com.unihub.shared.security.Permissions).WORKSHOP_EDIT)";

    private final RequestCoverImageUploadUseCase requestUpload;
    private final ConfirmCoverImageUploadUseCase confirmUpload;

    CoverImageController(
            RequestCoverImageUploadUseCase requestUpload,
            ConfirmCoverImageUploadUseCase confirmUpload) {
        this.requestUpload = requestUpload;
        this.confirmUpload = confirmUpload;
    }

    @Operation(
            operationId = "requestCoverImageUploadUrl",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = "[\"WORKSHOP_CREATE\", \"WORKSHOP_EDIT\"]",
                                            parseValue = true)),
            tags = {"Workshop"},
            parameters = {
                @Parameter(
                        name = "id",
                        in = ParameterIn.PATH,
                        required = true,
                        schema =
                                @Schema(
                                        types = {"string"},
                                        format = "uuid"))
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
                            schema = @Schema(implementation = CoverImageUploadUrlResponse.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @ApiProblems.NotFound
    @ApiProblems.Conflict
    @PostMapping(
            path = "/cover-image-upload-url",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(EITHER_PERMISSION)
    CoverImageUploadUrlResponse issue(
            @PathVariable UUID id,
            @RequestBody @Valid CoverImageUploadRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var result =
                requestUpload.requestUpload(
                        new RequestCoverImageUploadCommand(
                                id,
                                request.contentType(),
                                request.contentLength(),
                                CurrentActor.from(jwt)));
        return new CoverImageUploadUrlResponse(result.uploadUrl(), result.expiresAt());
    }

    @Operation(
            operationId = "confirmCoverImageUpload",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = "[\"WORKSHOP_CREATE\", \"WORKSHOP_EDIT\"]",
                                            parseValue = true)),
            tags = {"Workshop"},
            parameters = {
                @Parameter(
                        name = "id",
                        in = ParameterIn.PATH,
                        required = true,
                        schema =
                                @Schema(
                                        types = {"string"},
                                        format = "uuid"))
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
                            schema =
                                    @Schema(
                                            implementation =
                                                    CoverImageUploadConfirmResponse.class)))
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
    @PostMapping(path = "/cover-image-upload-confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(EITHER_PERMISSION)
    CoverImageUploadConfirmResponse confirm(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var result =
                confirmUpload.confirmUpload(
                        new ConfirmCoverImageUploadCommand(id, CurrentActor.from(jwt)));
        return new CoverImageUploadConfirmResponse(result.coverImageUrl());
    }
}
