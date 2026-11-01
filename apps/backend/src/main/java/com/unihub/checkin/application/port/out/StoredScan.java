package com.unihub.checkin.application.port.out;

import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanOutcome;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import java.time.Instant;

/**
 * A row of {@code check_in_scans} as stored; {@code registrationId} is null for an unresolved scan.
 */
public record StoredScan(
        ScanId id,
        RegistrationId registrationId,
        UserId scannedBy,
        String deviceId,
        Instant scannedAt,
        ScanOutcome outcome) {}
