package com.unihub.checkin.archfixture;

import com.unihub.registration.application.port.out.TicketSigner;
import com.unihub.registration.domain.model.Ticket;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;

/**
 * A class of another module that names the ticket signer: exactly what the D47 rule forbids (Phase
 * 5 checklist AC47.1). A plain class, never a bean (see {@code ChannelUser}). {@code
 * TicketKeyRulesTest} imports it by name; the production ArchUnit suites do not include test
 * classes.
 */
public final class SignerUser {
    private final TicketSigner signer;

    public SignerUser(TicketSigner signer) {
        this.signer = signer;
    }

    public Ticket sign(WorkshopId workshop, Instant at) {
        return signer.issue(workshop, at);
    }
}
