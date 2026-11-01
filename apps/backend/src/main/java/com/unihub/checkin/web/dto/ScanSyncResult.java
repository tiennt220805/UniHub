package com.unihub.checkin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** packages/api-contract {@code ScanSyncResult}. */
@Schema(
        types = {"object"},
        requiredProperties = {"id", "outcome"})
public record ScanSyncResult(UUID id, CheckInScanOutcome outcome) {}
