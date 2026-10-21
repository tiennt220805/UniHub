package com.unihub.registration.application.event;

import com.unihub.registration.api.event.RegistrationConfirmedIntegrationEvent;
import com.unihub.registration.domain.event.RegistrationConfirmed;
import com.unihub.registration.domain.event.RegistrationDomainEvent;
import com.unihub.registration.domain.event.SeatReserved;
import com.unihub.shared.event.OutboxMessage;
import java.util.List;

/**
 * Maps every Registration domain event to the outbox messages it produces (backend-coding-standards
 * .md §8.1). A domain event never goes on the wire itself. The switch is exhaustive over the sealed
 * {@link RegistrationDomainEvent}, so a new event does not compile until this decides what, if
 * anything, it publishes.
 */
public final class RegistrationOutboxTranslator {

    private RegistrationOutboxTranslator() {}

    public static List<OutboxMessage> translate(RegistrationDomainEvent event) {
        return switch (event) {
            case RegistrationConfirmed e ->
                    List.of(
                            new RegistrationConfirmedIntegrationEvent(
                                    e.registrationId().value(),
                                    e.workshopId().value(),
                                    e.userId().value(),
                                    e.occurredAt()));
            case SeatReserved e ->
                    List.of(
                            new SeatHoldExpiryRequested(
                                    e.registrationId().value(),
                                    e.workshopId().value(),
                                    e.holdExpiresAt(),
                                    e.occurredAt()));
        };
    }
}
