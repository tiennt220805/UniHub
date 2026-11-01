package com.unihub.registration.api;

import com.unihub.shared.domain.id.RegistrationId;
import java.util.Optional;

/**
 * Registration's answer to "what is the QR payload of this ticket?" for Notification's confirmation
 * email (Phase 5 checklist D55, task 6.1; backend-coding-standards.md §7, interaction type 1). Read
 * at the Master in its own transaction: the consumer runs right after the commit that signed the
 * ticket, and a lagging Replica could still show the row without one.
 */
public interface RegistrationTickets {

    /**
     * The signed QR payload (a compact JWS) of the registration. Empty when the registration does
     * not exist or has no ticket: a row confirmed before Phase 5 was never signed and is not
     * backfilled (checkin.md §4).
     */
    Optional<String> qrPayloadOf(RegistrationId registrationId);
}
