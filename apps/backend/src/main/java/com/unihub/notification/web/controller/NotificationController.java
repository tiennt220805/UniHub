package com.unihub.notification.web.controller;

import com.unihub.notification.application.port.in.ListMyNotificationsUseCase;
import com.unihub.notification.web.dto.NotificationDeliveryStatus;
import com.unihub.notification.web.dto.NotificationListResponse;
import com.unihub.notification.web.mapper.NotificationWebMapper;
import com.unihub.shared.web.ApiProblems;
import com.unihub.shared.web.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listMyNotifications} (packages/api-contract/paths/notification.yaml, Phase 4 checklist
 * D38). Ownership by construction, not by permission: any authenticated caller may ask, and the
 * list is always the JWT subject's own; there is no way to name another user. The annotations only
 * DESCRIBE the contract for contract-diff.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {
    private static final String CORRELATION_ID_PATTERN = "^[0-9a-f]{32}$";

    private final ListMyNotificationsUseCase listMyNotifications;

    NotificationController(ListMyNotificationsUseCase listMyNotifications) {
        this.listMyNotifications = listMyNotifications;
    }

    @Operation(
            operationId = "listMyNotifications",
            tags = {"Notification"},
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
                            schema = @Schema(implementation = NotificationListResponse.class)))
    @ApiProblems.Unauthorized
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    NotificationListResponse listMine(
            @RequestParam(required = false) NotificationDeliveryStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return NotificationWebMapper.toResponse(
                listMyNotifications.list(
                        NotificationWebMapper.toQuery(status, page, size, CurrentActor.from(jwt))));
    }
}
