package com.unihub.checkin.infrastructure.out.reads;

import com.unihub.checkin.application.port.out.ResolvedTicket;
import com.unihub.checkin.application.port.out.TicketStandings;
import com.unihub.checkin.domain.model.TicketStanding;
import com.unihub.registration.api.TicketLookup;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.WorkshopSummaries;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link TicketStandings} over {@code registration.api} and {@code workshop.api} (Phase 5 checklist
 * D51): translation only. A batch holds the tickets of a few workshops at most, so each distinct
 * workshop is read once.
 *
 * <p>A workshop that cannot be read counts as cancelled: not knowing that the room is open must
 * never turn a scan into an attendance.
 */
@Component
class RegistrationApiTicketStandings implements TicketStandings {
    private final TicketLookup tickets;
    private final WorkshopSummaries workshops;

    RegistrationApiTicketStandings(TicketLookup tickets, WorkshopSummaries workshops) {
        this.tickets = tickets;
        this.workshops = workshops;
    }

    @Override
    public Map<String, ResolvedTicket> findByCodes(Collection<String> ticketCodes) {
        var registrations = tickets.findByCodes(ticketCodes);
        var cancelled = new HashMap<WorkshopId, Boolean>();
        var resolved = new HashMap<String, ResolvedTicket>();
        registrations.forEach(
                (code, registration) -> {
                    var workshopCancelled =
                            cancelled.computeIfAbsent(registration.workshopId(), this::isCancelled);
                    resolved.put(
                            code,
                            new ResolvedTicket(
                                    registration.registrationId(),
                                    new TicketStanding(
                                            registration.confirmed(),
                                            registration.workshopId(),
                                            workshopCancelled)));
                });
        return resolved;
    }

    private boolean isCancelled(WorkshopId workshop) {
        return workshops.find(workshop).map(summary -> summary.cancelled()).orElse(true);
    }
}
