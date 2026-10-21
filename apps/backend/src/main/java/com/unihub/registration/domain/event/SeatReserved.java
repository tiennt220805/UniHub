package com.unihub.registration.domain.event;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/** A paid registration took a seat and holds it until {@code holdExpiresAt}. */
public record SeatReserved(
        RegistrationId registrationId,
        WorkshopId workshopId,
        UserId userId,
        Instant holdExpiresAt,
        Instant occurredAt)
        implements RegistrationDomainEvent {}
