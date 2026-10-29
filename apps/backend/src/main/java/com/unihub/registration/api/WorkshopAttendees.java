package com.unihub.registration.api;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import java.util.List;
import java.util.Optional;

/**
 * Registration's answer to "who has to hear about this workshop?" for Notification (Phase 4
 * checklist D35; backend-coding-standards.md §7, interaction type 1). Each call is its own read at
 * the Master, not inside the caller's transaction.
 */
public interface WorkshopAttendees {

    /**
     * The registrations of the workshop that hold a seat, {@code CONFIRMED} or {@code RESERVED}
     * ({@code EXPIRED} and {@code CANCELLED} are left out), oldest first, so the order is the same
     * on every retry of a fan-out. Read when the message is consumed, not when it was published.
     *
     * <p>When "workshop cancelled → cancel its registrations" is built (backend-coding-standards.md
     * §27 #8), registrations cancelled <em>because the workshop was cancelled</em> must still be
     * returned here, or their students would never learn of the cancellation.
     */
    List<Attendee> holdingSeatsFor(WorkshopId workshopId);

    /** One registration in any status; empty when it does not exist. */
    Optional<Attendee> find(RegistrationId registrationId);
}
