package com.unihub.checkin.application.port.out;

import com.unihub.checkin.domain.model.ScanId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import java.time.Instant;

/**
 * A scan about to be stored (database-schema.md §6.1).
 *
 * @param registrationId the registration the verified ticket code resolved to, or {@code null} for
 *     a scan the device rejected or whose code resolved to nothing
 * @param scannedBy the caller of the sync, never a value from the request body
 * @param scannedAt the device's clock, which is what reconciliation compares
 */
public record NewScan(
        ScanId id,
        RegistrationId registrationId,
        UserId scannedBy,
        String deviceId,
        Instant scannedAt) {}
