package com.unihub.registration.api;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;

/** A student's registration for a workshop, as far as Notification needs to know it. */
public record Attendee(RegistrationId registrationId, UserId userId, WorkshopId workshopId) {}
