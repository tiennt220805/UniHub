package com.unihub.registration.application.usecase;

import com.unihub.registration.api.event.RegistrationConfirmationRejectedIntegrationEvent;
import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.event.RegistrationOutboxTranslator;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.port.out.TicketSigner;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.security.Actor;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at confirming a registration whose payment succeeded, in one transaction at the
 * Master (payment.md §9, checklist D2 constraint 1). The status is read first and decides: {@code
 * RESERVED} is confirmed by a conditional update that also writes its signed e-ticket (and {@code
 * ticket.confirmed} is written in the same transaction), {@code CONFIRMED} is a redelivery and a
 * no-op, and {@code EXPIRED} or {@code CANCELLED} is not re-granted: a rejection is published for
 * Payment instead. A lost race on the conditional update is an {@code OptimisticConflictException}
 * for the caller to retry, which re-reads and so lands on one of the other branches.
 */
@Service
class ConfirmRegistrationTransaction {

    enum Effect {
        CONFIRMED,
        ALREADY_CONFIRMED,
        REJECTED
    }

    static final String CONFIRMATION_PROCESS = "payment-confirmation";

    private final RegistrationRepository registrations;
    private final EventPublisher events;
    private final AuditTrail auditTrail;
    private final TicketSigner ticketSigner;
    private final Clock clock;

    ConfirmRegistrationTransaction(
            RegistrationRepository registrations,
            EventPublisher events,
            AuditTrail auditTrail,
            TicketSigner ticketSigner,
            Clock clock) {
        this.registrations = registrations;
        this.events = events;
        this.auditTrail = auditTrail;
        this.ticketSigner = ticketSigner;
        this.clock = clock;
    }

    @Transactional
    Effect attempt(ConfirmRegistrationCommand command) {
        var registration =
                registrations
                        .findById(command.registrationId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "payment "
                                                        + command.paymentId().value()
                                                        + " succeeded for unknown registration "
                                                        + command.registrationId().value()));
        var now = clock.instant();
        switch (registration.status()) {
            case RESERVED -> {
                // Signed only in this branch: a redelivery finds CONFIRMED below and never signs
                // again, so the ticket a student holds is the one first issued (checkin.md §4).
                registration.confirm(now, ticketSigner.issue(registration.workshopId(), now));
                var recorded = registration.pullEvents();
                registrations.transition(registration, RegistrationStatus.RESERVED);
                recorded.stream()
                        .flatMap(e -> RegistrationOutboxTranslator.translate(e).stream())
                        .forEach(events::publish);
                // The consumer confirms, not a user: a process, named as such (checklist D6).
                auditTrail.record(
                        AuditEntry.of(
                                        Actor.system(CONFIRMATION_PROCESS),
                                        AuditActions.REGISTRATION_CONFIRMED,
                                        AuditTargets.REGISTRATION,
                                        registration.id().value(),
                                        now)
                                .withMetadata(
                                        Map.of(
                                                "paymentId",
                                                command.paymentId().value().toString())));
                return Effect.CONFIRMED;
            }
            case CONFIRMED -> {
                return Effect.ALREADY_CONFIRMED;
            }
            case EXPIRED, CANCELLED -> {
                events.publish(
                        new RegistrationConfirmationRejectedIntegrationEvent(
                                command.paymentId().value(),
                                command.registrationId().value(),
                                registration.status() == RegistrationStatus.EXPIRED
                                        ? RegistrationConfirmationRejectedIntegrationEvent
                                                .HOLD_EXPIRED
                                        : RegistrationConfirmationRejectedIntegrationEvent
                                                .REGISTRATION_CANCELLED,
                                now));
                return Effect.REJECTED;
            }
        }
        throw new IllegalStateException("unreachable: " + registration.status());
    }
}
