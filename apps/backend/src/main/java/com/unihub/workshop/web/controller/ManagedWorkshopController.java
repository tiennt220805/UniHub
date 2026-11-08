package com.unihub.workshop.web.controller;

import com.unihub.shared.security.Permissions;
import com.unihub.shared.web.ApiProblems;
import com.unihub.workshop.application.port.in.ListManagedWorkshopsUseCase;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.dto.WorkshopStatus;
import com.unihub.workshop.web.mapper.WorkshopWebMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listManagedWorkshops} (packages/api-contract paths/workshop.yaml, Phase 8 D119): the
 * Organizer's list of workshops of every status. A separate controller and path from the public
 * catalog so the catalog's operation, cache key and payload stay as they are. {@code WORKSHOP_EDIT}
 * only: creating a workshop does not entitle a caller to read the others.
 */
@RestController
@RequestMapping("/api/v1/admin/workshops")
class ManagedWorkshopController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final ListManagedWorkshopsUseCase listManagedWorkshops;

    ManagedWorkshopController(ListManagedWorkshopsUseCase listManagedWorkshops) {
        this.listManagedWorkshops = listManagedWorkshops;
    }

    @Operation(
            operationId = "listManagedWorkshops",
            extensions =
                    @Extension(
                            properties =
                                    @ExtensionProperty(
                                            name = "x-required-permission",
                                            value = Permissions.WORKSHOP_EDIT)),
            tags = {"Workshop"})
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
    @ApiProblems.BadRequest
    @ApiProblems.Unauthorized
    @ApiProblems.Forbidden
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.unihub.shared.security.Permissions).WORKSHOP_EDIT)")
    WorkshopListResponse list(
            @RequestParam(required = false) WorkshopStatus status,
            @RequestParam(required = false) @Size(max = 255) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var criteria = WorkshopWebMapper.toManagedCriteria(status, q, page, size);
        return WorkshopWebMapper.toResponse(listManagedWorkshops.list(criteria));
    }
}
