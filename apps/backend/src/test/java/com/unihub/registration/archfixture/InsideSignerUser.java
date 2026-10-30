package com.unihub.registration.archfixture;

import com.unihub.registration.application.port.out.TicketSigner;
import com.unihub.registration.domain.model.Ticket;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/**
 * Names the signer from inside Registration, which is allowed (D47). A plain class, never a bean.
 */
public final class InsideSignerUser {
    private final TicketSigner signer;

    public InsideSignerUser(TicketSigner signer) {
        this.signer = signer;
    }

    public Ticket sign(WorkshopId workshop, Instant at) {
        return signer.issue(workshop, at);
    }
}
