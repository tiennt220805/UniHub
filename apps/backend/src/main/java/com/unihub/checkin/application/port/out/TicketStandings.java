package com.unihub.checkin.application.port.out;

import java.util.Collection;
import java.util.Map;

/**
 * What the server knows about the tickets of a sync batch (Phase 5 checklist D51): one read per
 * batch, at the Master, so a registration confirmed or a workshop cancelled a moment ago is seen as
 * such. Owned by Check-in; the adapter answers from Registration's and Workshop's {@code api}.
 */
public interface TicketStandings {

    /** The resolved ticket of each code that belongs to a registration, keyed by code. */
    Map<String, ResolvedTicket> findByCodes(Collection<String> ticketCodes);
}
