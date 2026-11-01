package com.unihub.checkin.web.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** packages/api-contract {@code SyncScanBatchRequest}: 1 to 500 scans. */
@Schema(
        types = {"object"},
        requiredProperties = {"scans"})
public record SyncScanBatchRequest(
        // @Size validates; @ArraySchema describes: springdoc does not read the bounds off @Size.
        @NotNull @Size(min = 1, max = 500) @ArraySchema(minItems = 1, maxItems = 500)
                List<@Valid ScanEvent> scans) {}
