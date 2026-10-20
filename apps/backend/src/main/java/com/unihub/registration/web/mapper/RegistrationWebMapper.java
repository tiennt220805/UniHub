package com.unihub.registration.web.mapper;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.web.dto.CreateRegistrationRequest;
import com.unihub.registration.web.dto.Registration;
import com.unihub.registration.web.dto.RegistrationStatus;
import com.unihub.registration.web.dto.TicketType;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;

public final class RegistrationWebMapper {

    private RegistrationWebMapper() {}

    public static ReserveSeatCommand toCommand(
            CreateRegistrationRequest request, Actor.UserActor actor) {
        return new ReserveSeatCommand(actor, WorkshopId.of(request.workshopId()));
    }

    /**
     * The 201 body for a registration the use case created. The ticket type follows from the
     * outcome (a hold is a paid ticket, an immediate confirmation a free one) — the server's
     * decision, never the client's.
     */
    public static Registration toResponse(
            ReserveSeatCommand command, ReserveSeatResult.Reserved reserved) {
        return new Registration(
                reserved.id().value(),
                command.workshopId().value(),
                command.actor().userId().value(),
                TicketType.PAID,
                RegistrationStatus.RESERVED,
                reserved.holdExpiresAt(),
                null,
                null,
                reserved.createdAt(),
                reserved.updatedAt());
    }

    public static Registration toResponse(
            ReserveSeatCommand command, ReserveSeatResult.Confirmed confirmed) {
        return new Registration(
                confirmed.id().value(),
                command.workshopId().value(),
                command.actor().userId().value(),
                TicketType.FREE,
                RegistrationStatus.CONFIRMED,
                null,
                null,
                null,
                confirmed.createdAt(),
                confirmed.updatedAt());
    }
}
