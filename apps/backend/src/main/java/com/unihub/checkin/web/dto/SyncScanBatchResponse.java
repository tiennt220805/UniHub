package com.unihub.checkin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** packages/api-contract {@code SyncScanBatchResponse}. */
@Schema(
        types = {"object"},
        requiredProperties = {"results"})
public record SyncScanBatchResponse(List<ScanSyncResult> results) {}
