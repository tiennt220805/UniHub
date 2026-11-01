package com.unihub.checkin.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * packages/api-contract {@code ScanEvent}. {@code ticketCode} is a {@link JsonNode} on purpose: for
 * a scan the device rejected it is attacker-controlled and may be any JSON value, which must not
 * fail the whole batch (Phase 5 checklist D50, the poison-batch rule). Only a {@code VERIFIED}
 * scan's code is read, and the mapper checks it. There is no scanner field: {@code scanned_by} is
 * the caller of the sync.
 */
@Schema(
        types = {"object"},
        requiredProperties = {"id", "deviceVerdict", "workshopId", "scannedAt", "deviceId"})
public record ScanEvent(
        @NotNull UUID id,
        @NotNull DeviceVerdict deviceVerdict,
        JsonNode ticketCode,
        @NotNull UUID workshopId,
        @NotNull Instant scannedAt,
        @NotNull @Size(min = 1, max = 100) String deviceId) {}
