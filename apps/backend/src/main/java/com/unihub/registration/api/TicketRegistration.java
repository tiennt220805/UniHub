package com.unihub.registration.api;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;

/**
 * The registration behind a ticket code, as far as Check-in needs to judge a scan of it: whose
 * ticket it is and whether it still counts. Nothing about the student leaves Registration.
 *
 * @param workshopId the ticket's workshop, which the server takes from the registration (it never
 *     sees the signed payload)
 * @param confirmed the registration is {@code CONFIRMED}; reserved, expired and cancelled ones are
 *     not
 */
public record TicketRegistration(
        RegistrationId registrationId, WorkshopId workshopId, boolean confirmed) {}
