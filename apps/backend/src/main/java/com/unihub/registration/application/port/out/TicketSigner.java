package com.unihub.registration.application.port.out;

import com.unihub.registration.api.PublicTicketKey;
import com.unihub.registration.domain.model.Ticket;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/**
 * Issues the e-ticket of a registration that becomes {@code CONFIRMED} (checkin.md §4; Phase 5
 * checklist D47, D48). A stateless computation with no network I/O, so it may run inside the
 * confirming transaction (backend-coding-standards.md §19); the signature is made with the one key
 * only the Registration module holds.
 */
public interface TicketSigner {

    /**
     * A new ticket: a fresh unguessable {@code ticket_code}, and the compact JWS that signs {@code
     * {ticket_code, workshop_id, iat}} — no expiry, no personal data (checkin.md §3). {@code
     * issuedAt} is the confirmation instant, passed in because nothing here reads a clock.
     */
    Ticket issue(WorkshopId workshopId, Instant issuedAt);

    /** The public key that verifies what {@link #issue} signs. */
    PublicTicketKey publicKey();
}
