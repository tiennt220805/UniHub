package com.unihub.workshop.application.event;

import com.unihub.shared.event.OutboxMessage;
import com.unihub.workshop.api.event.WorkshopCancelledIntegrationEvent;
import com.unihub.workshop.api.event.WorkshopRescheduledIntegrationEvent;
import com.unihub.workshop.domain.event.WorkshopCancelled;
import com.unihub.workshop.domain.event.WorkshopDomainEvent;
import com.unihub.workshop.domain.event.WorkshopRescheduled;
import java.util.List;

/**
 * Maps every Workshop domain event to the outbox messages it produces (backend-coding-standards.md
 * §8.1). A domain event never goes on the wire itself. The switch is exhaustive over the sealed
 * {@link WorkshopDomainEvent}, so a new event does not compile until this decides what, if
 * anything, it publishes.
 */
public final class WorkshopOutboxTranslator {

    private WorkshopOutboxTranslator() {}

    public static List<OutboxMessage> translate(WorkshopDomainEvent event) {
        return switch (event) {
            case WorkshopCancelled e ->
                    List.of(
                            new WorkshopCancelledIntegrationEvent(
                                    e.workshopId().value(), e.occurredAt()));
            case WorkshopRescheduled e ->
                    List.of(
                            new WorkshopRescheduledIntegrationEvent(
                                    e.workshopId().value(),
                                    e.previousStartTime(),
                                    e.previousEndTime(),
                                    e.newStartTime(),
                                    e.newEndTime(),
                                    e.occurredAt()));
        };
    }
}
