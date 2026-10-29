package com.unihub.notification.application.port.out;

import java.util.UUID;

/** A student's registration for a workshop. */
public record RegistrationRef(UUID registrationId, UUID userId, UUID workshopId) {}
