package com.unihub.workshop.web.controller;

import com.unihub.shared.web.dto.ProblemSchema;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.mapper.WorkshopWebMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// GET /api/v1/workshops is public (packages/api-contract/paths/workshop.yaml: security: []) — no
// @PreAuthorize: SecurityConfig permits this path; the empty @SecurityRequirements below mirrors
// it.
@RestController
@RequestMapping("/api/v1/workshops")
class WorkshopController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final SearchWorkshopsUseCase searchWorkshops;

    WorkshopController(SearchWorkshopsUseCase searchWorkshops) {
        this.searchWorkshops = searchWorkshops;
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
}
