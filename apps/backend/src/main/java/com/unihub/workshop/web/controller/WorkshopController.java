package com.unihub.workshop.web.controller;

import com.unihub.shared.security.Permissions;
import com.unihub.shared.web.ApiProblems;
import com.unihub.shared.web.CurrentActor;
import com.unihub.shared.web.dto.ProblemSchema;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.port.in.CancelWorkshopUseCase;
import com.unihub.workshop.application.port.in.CreateWorkshopUseCase;
import com.unihub.workshop.application.port.in.GetWorkshopUseCase;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.application.port.in.UpdateWorkshopUseCase;
import com.unihub.workshop.web.dto.CreateWorkshopRequest;
import com.unihub.workshop.web.dto.UpdateWorkshopRequest;
import com.unihub.workshop.web.dto.Workshop;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.mapper.WorkshopWebMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// GET /api/v1/workshops is public (packages/api-contract/paths/workshop.yaml: security: []) — no
// @PreAuthorize: SecurityConfig permits this path; the empty @SecurityRequirements below mirrors
// it. The write endpoints require a token and name their permission (backend-coding-standards.md
// §11); a controller only translates HTTP to a Command and back.
@RestController
@RequestMapping("/api/v1/workshops")
class WorkshopController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final SearchWorkshopsUseCase searchWorkshops;
    private final GetWorkshopUseCase getWorkshop;
    private final CreateWorkshopUseCase createWorkshop;
    private final UpdateWorkshopUseCase updateWorkshop;
    private final CancelWorkshopUseCase cancelWorkshop;

    WorkshopController(
            SearchWorkshopsUseCase searchWorkshops,
            GetWorkshopUseCase getWorkshop,
            CreateWorkshopUseCase createWorkshop,
            UpdateWorkshopUseCase updateWorkshop,
            CancelWorkshopUseCase cancelWorkshop) {
        this.searchWorkshops = searchWorkshops;
        this.getWorkshop = getWorkshop;
        this.createWorkshop = createWorkshop;
        this.updateWorkshop = updateWorkshop;
        this.cancelWorkshop = cancelWorkshop;
    }

    // The annotations below only DESCRIBE the contract (packages/api-contract/paths/workshop.yaml,
    // listWorkshops) so contract-diff can compare shapes. Behaviour behind them, today:
    //  - page/size bounds (@Min/@Max): real — a violation is a 400 problem+json via
    // GlobalProblemHandler.
    //  - 429 + Retry-After: documented only — no rate limiter exists (distributed-coordination.md
    // §3).
    //  - X-Correlation-Id (request param and 200 header): documented only — no correlation-ID
    // filter
    //    exists, so the server neither reads nor echoes it.
    @Operation(
            operationId = "listWorkshops",
            tags = {"Workshop"},
            parameters =
                    @Parameter(
                            name = "X-Correlation-Id",
                            in = ParameterIn.HEADER,
                            required = false,
                            schema =
                                    @Schema(
                                            types = {"string"},
                                            pattern = CORRELATION_ID_PATTERN)))
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
                            schema = @Schema(implementation = WorkshopListResponse.class)))
    @ApiResponse(
            responseCode = "400",
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
    @SecurityRequirements // public: the contract says `security: []`, overriding the global
    // bearerAuth
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    WorkshopListResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String speaker,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var criteria =
                WorkshopWebMapper.toCriteria(q, speaker, location, dateFrom, dateTo, page, size);
        var result = searchWorkshops.search(criteria);
        return WorkshopWebMapper.toResponse(result);
    }

    // Public like the list (contract: security: []), but the bearer token is optional input: a
    // caller holding WORKSHOP_CREATE/WORKSHOP_EDIT may see a DRAFT, anyone else gets 404 for it.
    // 429 and X-Correlation-Id are documented only, as on the list endpoint.
    @Operation(
            operationId = "getWorkshop",
            tags = {"Workshop"},
            parameters = {
                @Parameter(
                        name = "id",
                        in = ParameterIn.PATH,
                        required = true,
                        schema =
                                @Schema(
                                        types = {"string"},
                                        format = "uuid")),
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
                            schema = @Schema(implementation = Workshop.class)))
    @ApiProblems.NotFound
    @ApiResponse(
            responseCode = "429",
            headers = @Header(name = "Retry-After", schema = @Schema(types = {"integer"})),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemSchema.class)))
    @SecurityRequirements
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Workshop detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Set<String> permissions = jwt == null ? Set.of() : CurrentActor.from(jwt).permissions();
        return WorkshopWebMapper.toResponse(getWorkshop.get(new GetWorkshopQuery(id, permissions)));
    }

    @Operation(
            operationId = "createWorkshop",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.WORKSHOP_CREATE)),
            tags = {"Workshop"},
            parameters =
                    @Parameter(
                            name = "X-Correlation-Id",
                            in = ParameterIn.HEADER,
                            required = false,
                            schema =
                                    @Schema(
                                            types = {"string"},
                                            pattern = CORRELATION_ID_PATTERN)))
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
                            schema = @Schema(implementation = Workshop.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).WORKSHOP_CREATE)")
    ResponseEntity<Workshop> create(
            @Valid @RequestBody CreateWorkshopRequest request, @AuthenticationPrincipal Jwt jwt) {
        var result =
                createWorkshop.create(WorkshopWebMapper.toCommand(request, CurrentActor.from(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkshopWebMapper.toResponse(result));
    }

    // Edit and reschedule are one operation: a reschedule is a PATCH that sets only
    // startTime/endTime. Its 409 (terminal workshop, lost version race) is not in the contract's
    // updateWorkshop yet — see the M-04 report.
    @Operation(
            operationId = "updateWorkshop",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.WORKSHOP_EDIT)),
            tags = {"Workshop"},
            parameters = {
                @Parameter(
                        name = "id",
                        in = ParameterIn.PATH,
                        required = true,
                        schema =
                                @Schema(
                                        types = {"string"},
                                        format = "uuid")),
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
                            schema = @Schema(implementation = Workshop.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @ApiProblems.NotFound
    @ApiProblems.Conflict
    @PatchMapping(
            path = "/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).WORKSHOP_EDIT)")
    Workshop update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkshopRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var result =
                updateWorkshop.update(
                        WorkshopWebMapper.toCommand(id, request, CurrentActor.from(jwt)));
        return WorkshopWebMapper.toResponse(result);
    }

    @Operation(
            operationId = "cancelWorkshop",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.WORKSHOP_EDIT)),
            tags = {"Workshop"},
            parameters = {
                @Parameter(
                        name = "id",
                        in = ParameterIn.PATH,
                        required = true,
                        schema =
                                @Schema(
                                        types = {"string"},
                                        format = "uuid")),
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
                            schema = @Schema(implementation = Workshop.class)))
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @ApiProblems.NotFound
    @ApiProblems.Conflict
    @PostMapping(path = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).WORKSHOP_EDIT)")
    Workshop cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var result =
                cancelWorkshop.cancel(
                        WorkshopWebMapper.toCancelCommand(id, CurrentActor.from(jwt)));
        return WorkshopWebMapper.toResponse(result);
    }
}
