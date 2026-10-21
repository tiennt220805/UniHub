package com.unihub.registration.api;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/**
 * What Payment needs to know about a registration it is asked to charge for: who owns it, which
 * workshop it is for, and whether its seat hold is still ours to pay for. Nothing else of the
 * aggregate leaves Registration.
 *
 * @param reserved the registration is {@code RESERVED} (not confirmed, cancelled or expired)
 * @param holdExpiresAt when the hold ends; set exactly when {@code reserved}
 */
public record PayableRegistration(
        RegistrationId id,
        WorkshopId workshopId,
        UserId userId,
        boolean reserved,
        Instant holdExpiresAt) {}
