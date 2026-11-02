package com.unihub.checkin.web.controller;

import com.unihub.checkin.application.port.in.GetWorkshopReportUseCase;
import com.unihub.checkin.application.port.in.ListWorkshopAttendancesUseCase;
import com.unihub.checkin.application.query.ListWorkshopAttendancesQuery;
import com.unihub.checkin.web.dto.AttendanceListResponse;
import com.unihub.checkin.web.dto.WorkshopReport;
import com.unihub.checkin.web.mapper.CheckInWebMapper;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Permissions;
import com.unihub.shared.web.ApiProblems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getWorkshopReport} and {@code listWorkshopAttendances}
 * (packages/api-contract/paths/checkin.yaml, Phase 5 checklist D46). Permission {@code
 * REPORT_VIEW}, held by Organizer and Admin: a permission, not a per-workshop grant, because {@code
 * workshops} has no owner column. The annotations on the methods only DESCRIBE the contract for
 * contract-diff.
 */
@RestController
@RequestMapping("/api/v1/workshops/{workshopId}")
class WorkshopReportController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final GetWorkshopReportUseCase getReport;
    private final ListWorkshopAttendancesUseCase listAttendances;

    WorkshopReportController(
            GetWorkshopReportUseCase getReport, ListWorkshopAttendancesUseCase listAttendances) {
        this.getReport = getReport;
        this.listAttendances = listAttendances;
    }

    @Operation(
            operationId = "getWorkshopReport",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.REPORT_VIEW)),
            tags = {"Check-in"})
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
                            schema = @Schema(implementation = WorkshopReport.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @ApiProblems.NotFound
    @GetMapping(path = "/report", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).REPORT_VIEW)")
    WorkshopReport report(@PathVariable UUID workshopId) {
        return CheckInWebMapper.toResponse(getReport.report(WorkshopId.of(workshopId)));
    }

    @Operation(
            operationId = "listWorkshopAttendances",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.REPORT_VIEW)),
            tags = {"Check-in"})
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
                            schema = @Schema(implementation = AttendanceListResponse.class)))
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @ApiProblems.NotFound
    @GetMapping(path = "/attendances", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).REPORT_VIEW)")
    AttendanceListResponse attendances(
            @PathVariable UUID workshopId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return CheckInWebMapper.toResponse(
                listAttendances.list(
                        new ListWorkshopAttendancesQuery(WorkshopId.of(workshopId), page, size)));
    }
}
