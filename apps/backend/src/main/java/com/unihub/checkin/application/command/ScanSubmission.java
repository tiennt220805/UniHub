package com.unihub.checkin.application.command;

import com.unihub.checkin.domain.model.DeviceVerdict;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;
import java.util.Objects;

/**
 * One scan of a sync batch, as the device queued it (checkin.md §7).
 *
 * @param ticketCode the verified claim; present exactly when {@code verdict} is {@code VERIFIED}.
 *     For a scan the device rejected the web layer passes nothing, whatever the request carried:
 *     the raw QR of a rejected scan is attacker-controlled and never reaches the use case
 * @param venue the workshop the staff member selected on the device
 */
public record ScanSubmission(
        ScanId id,
        DeviceVerdict verdict,
        String ticketCode,
        WorkshopId venue,
        Instant scannedAt,
        String deviceId) {

    public ScanSubmission {
        Objects.requireNonNull(id);
        Objects.requireNonNull(verdict);
        Objects.requireNonNull(venue);
        Objects.requireNonNull(scannedAt);
        Objects.requireNonNull(deviceId);
        if (verdict == DeviceVerdict.VERIFIED && (ticketCode == null || ticketCode.isBlank())) {
            throw new IllegalArgumentException("a verified scan carries the ticket code");
        }
        if (verdict == DeviceVerdict.REJECTED_SIGNATURE) {
            ticketCode = null;
        }
    }
}
