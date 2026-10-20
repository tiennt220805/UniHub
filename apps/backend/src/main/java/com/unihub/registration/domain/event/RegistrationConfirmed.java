package com.unihub.registration.domain.event;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/** The registration holds a confirmed seat: a free ticket at once, a paid one after payment. */
public record RegistrationConfirmed(
        RegistrationId registrationId, WorkshopId workshopId, UserId userId, Instant occurredAt)
        implements RegistrationDomainEvent {}
