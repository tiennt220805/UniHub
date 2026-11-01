package com.unihub.checkin.application.port.out;

import com.unihub.checkin.domain.model.TicketStanding;
import com.unihub.shared.domain.id.RegistrationId;

/** A ticket code that belongs to a registration, with what is needed to judge a scan of it. */
public record ResolvedTicket(RegistrationId registrationId, TicketStanding standing) {}
