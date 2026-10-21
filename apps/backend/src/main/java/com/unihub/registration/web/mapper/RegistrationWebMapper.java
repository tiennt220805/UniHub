package com.unihub.registration.web.mapper;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.query.GetRegistrationQuery;
import com.unihub.registration.application.query.ListMyRegistrationsQuery;
import com.unihub.registration.application.result.MyRegistrationsResult;
import com.unihub.registration.application.result.RegistrationResult;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.web.dto.CreateRegistrationRequest;
import com.unihub.registration.web.dto.Registration;
import com.unihub.registration.web.dto.RegistrationListResponse;
import com.unihub.registration.web.dto.RegistrationStatus;
import com.unihub.registration.web.dto.RegistrationSummary;
import com.unihub.registration.web.dto.TicketType;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import com.unihub.shared.web.dto.PageMeta;
import java.util.UUID;

public final class RegistrationWebMapper {

    private RegistrationWebMapper() {}

    public static ReserveSeatCommand toCommand(
            CreateRegistrationRequest request,
            String idempotencyKey,
            String requestFingerprint,
            Actor.UserActor actor) {
        return new ReserveSeatCommand(
                actor, WorkshopId.of(request.workshopId()), idempotencyKey, requestFingerprint);
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

    /** The owner's view of one registration (the same body as the 201 of createRegistration). */
    public static Registration toResponse(RegistrationResult r) {
        return new Registration(
                r.id(),
                r.workshopId(),
                r.userId(),
                TicketType.valueOf(r.ticketType()),
                RegistrationStatus.valueOf(r.status()),
                r.holdExpiresAt(),
                r.ticketCode(),
                r.qrPayload(),
                r.createdAt(),
                r.updatedAt());
    }

    public static RegistrationListResponse toResponse(MyRegistrationsResult result) {
        return new RegistrationListResponse(
                result.items().stream()
                        .map(
                                item ->
                                        new RegistrationSummary(
                                                item.id(),
                                                item.workshopId(),
                                                item.userId(),
                                                TicketType.valueOf(item.ticketType()),
                                                RegistrationStatus.valueOf(item.status()),
                                                item.createdAt()))
                        .toList(),
                new PageMeta(
                        result.page(), result.size(), result.totalElements(), result.totalPages()));
    }

    public static GetRegistrationQuery toQuery(UUID id, Actor.UserActor caller) {
        return new GetRegistrationQuery(caller.userId(), RegistrationId.of(id));
    }

    public static ListMyRegistrationsQuery toQuery(
            RegistrationStatus status, int page, int size, Actor.UserActor caller) {
        return new ListMyRegistrationsQuery(
                caller.userId(), status == null ? null : status.name(), page, size);
    }
}
