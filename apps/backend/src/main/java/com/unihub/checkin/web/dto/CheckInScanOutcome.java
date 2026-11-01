package com.unihub.checkin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// enumAsRef: the contract declares CheckInScanOutcome as a named component.
@Schema(enumAsRef = true)
public enum CheckInScanOutcome {
    ACCEPTED,
    REJECTED_DUPLICATE,
    REJECTED_INVALID
}
