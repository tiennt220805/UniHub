package com.unihub.checkin.application.result;

import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanOutcome;

/** The stored outcome of one scan at the moment the response is built. */
public record ScanResult(ScanId id, ScanOutcome outcome) {}
