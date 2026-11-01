package com.unihub.checkin.domain.model;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Objects;

/**
 * What the server knows about a ticket at the moment a scan of it is reconciled (checkin.md §7,
 * Phase 5 checklist D51), as read from Registration and Workshop at the Master.
 *
 * @param confirmed the registration is {@code CONFIRMED}; a reserved, expired or cancelled one is
 *     not
 * @param workshopId the ticket's workshop, taken from the registration (the server never sees the
 *     signed payload)
 * @param workshopCancelled that workshop is {@code CANCELLED}
 */
public record TicketStanding(boolean confirmed, WorkshopId workshopId, boolean workshopCancelled) {

    public TicketStanding {
        Objects.requireNonNull(workshopId);
    }
}
