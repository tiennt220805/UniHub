package com.unihub.registration.application.result;

import com.unihub.registration.domain.model.Registration;
import java.time.Instant;
import java.util.UUID;

/**
 * A registration as the owner sees it. {@code ticketCode}/{@code qrPayload} stay null until ticket
 * issuance exists (Phase 5); {@code holdExpiresAt} is set exactly while {@code RESERVED}.
 */
public record RegistrationResult(
        UUID id,
        UUID workshopId,
        UUID userId,
        String ticketType,
        String status,
        Instant holdExpiresAt,
        String ticketCode,
        String qrPayload,
        Instant createdAt,
        Instant updatedAt) {

    public static RegistrationResult of(Registration r) {
        return new RegistrationResult(
                r.id().value(),
                r.workshopId().value(),
                r.userId().value(),
                r.ticketType().name(),
                r.status().name(),
                r.holdExpiresAt(),
                r.ticketCode(),
                r.qrPayload(),
                r.createdAt(),
                r.updatedAt());
    }
}
