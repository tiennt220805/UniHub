package com.unihub.registration.api;

import java.util.Collection;
import java.util.Map;

/**
 * Registration's answer to "which registrations do these ticket codes belong to?" for Check-in
 * (Phase 5 checklist D51; backend-coding-standards.md §7, interaction type 1). One call per sync
 * batch, read at the Master in its own transaction: whether a registration is {@code CONFIRMED}
 * decides whether a scan counts, and a lagging Replica could still show it as {@code RESERVED}.
 */
public interface TicketLookup {

    /**
     * The registration of each code that belongs to one, keyed by code. A code nobody holds is
     * absent, never an error; so is a registration confirmed before Phase 5, which has no code.
     */
    Map<String, TicketRegistration> findByCodes(Collection<String> ticketCodes);
}
