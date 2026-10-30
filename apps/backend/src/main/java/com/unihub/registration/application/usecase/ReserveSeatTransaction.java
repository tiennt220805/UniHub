package com.unihub.registration.application.usecase;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.event.RegistrationOutboxTranslator;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.port.out.TicketSigner;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.model.Registration;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.usecase.IdempotentReplay;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.workshop.api.SeatClaimOutcome;
import com.unihub.workshop.api.SeatInventory;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at the seat reservation, in one transaction: the seat claim (Workshop, {@code
 * MANDATORY}) and the {@code registrations} insert commit together or not at all
 * (database-schema.md §4.1), the audit entry is recorded here (it takes effect after commit) and so
 * is the idempotency key, begun first and completed last. What the registration records also
 * becomes outbox rows here (INV-10): {@code ticket.confirmed} for a free ticket (signed in the same
 * transaction, so the e-ticket is there when the event is consumed), the hold-expiry job for a paid
 * hold. A row commits with the registration or not at all, and the relay publishes it later.
 */
@Service
class ReserveSeatTransaction {
    private final IdempotencyStore idempotency;
    private final SeatInventory seatInventory;
    private final RegistrationRepository registrations;
    private final AuditTrail auditTrail;
    private final EventPublisher events;
    private final RegistrationPolicy policy;
    private final TicketSigner ticketSigner;
    private final Clock clock;

    ReserveSeatTransaction(
            IdempotencyStore idempotency,
            SeatInventory seatInventory,
            RegistrationRepository registrations,
            AuditTrail auditTrail,
            EventPublisher events,
            RegistrationPolicy policy,
            TicketSigner ticketSigner,
            Clock clock) {
        this.idempotency = idempotency;
        this.seatInventory = seatInventory;
        this.registrations = registrations;
        this.auditTrail = auditTrail;
        this.events = events;
        this.policy = policy;
        this.ticketSigner = ticketSigner;
        this.clock = clock;
    }

    @Transactional
    public ReserveSeatResult execute(ReserveSeatCommand command) {
        var userId = command.actor().userId();
        var scope = IdempotencyScope.REGISTRATION_CREATE;

        // Backstop re-check: a duplicate may have committed while this request waited for the lock.
        var replay =
                IdempotentReplay.of(
                        idempotency,
                        scope,
                        userId,
                        command.idempotencyKey(),
                        command.requestFingerprint(),
                        ReserveSeatResult.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        // The first write of the transaction (§21 rule 2): a concurrent request with the same key
        // blocks here, then gets IdempotencyKeyInUseException if this one commits.
        idempotency.begin(
                scope, userId, command.idempotencyKey(), command.requestFingerprint(), null);

        var result = decide(command);

        // Every decided outcome is recorded, a rejection included (registration.md §4: "why a 409
        // also gets recorded"). The row commits with the effect, or rolls back with it.
        idempotency.complete(
                scope, userId, command.idempotencyKey(), result, registrationIdOrNull(result));
        return result;
    }

    private ReserveSeatResult decide(ReserveSeatCommand command) {
        var userId = command.actor().userId();
        var workshopId = command.workshopId();

        // Before the claim, not by catching the unique violation after it: a constraint violation
        // aborts the PostgreSQL transaction, so nothing could be decided inside it afterwards.
        if (registrations.existsActive(workshopId, userId)) {
            return new ReserveSeatResult.AlreadyRegistered();
        }

        return switch (seatInventory.claim(workshopId)) {
            case SeatClaimOutcome.SoldOut soldOut -> new ReserveSeatResult.SoldOut();
            case SeatClaimOutcome.NotOpenForRegistration closed ->
                    new ReserveSeatResult.NotOpenForRegistration();
            case SeatClaimOutcome.Claimed claimed -> {
                var id = RegistrationId.newId();
                var now = clock.instant();
                var registration =
                        claimed.paid()
                                ? Registration.reserve(
                                        id, workshopId, userId, now, policy.seatHoldDuration())
                                // Signed here, inside the transaction: a stateless computation
                                // (§19), so the INSERT carries status, ticket_code and qr_payload
                                // together (checkin.md §4).
                                : Registration.confirmFree(
                                        id,
                                        workshopId,
                                        userId,
                                        now,
                                        ticketSigner.issue(workshopId, now));
                // The saved aggregate carries the row's timestamps, which the response needs.
                var saved = registrations.save(registration);
                // In this transaction (EventPublisher is MANDATORY): the intent to publish commits
                // or rolls back with the registration, never alone (§8, INV-10).
                registration.pullEvents().stream()
                        .flatMap(event -> RegistrationOutboxTranslator.translate(event).stream())
                        .forEach(events::publish);
                // Deferred to after commit and fail-open (AuditTrail): never blocks the claim.
                auditTrail.record(
                        AuditEntry.of(
                                command.actor(),
                                AuditActions.REGISTRATION_CREATED,
                                AuditTargets.REGISTRATION,
                                id.value(),
                                now));
                yield saved.isConfirmed()
                        ? new ReserveSeatResult.Confirmed(
                                id,
                                saved.createdAt(),
                                saved.updatedAt(),
                                saved.ticketCode(),
                                saved.qrPayload())
                        : new ReserveSeatResult.Reserved(
                                id, saved.holdExpiresAt(), saved.createdAt(), saved.updatedAt());
            }
        };
    }

    private static UUID registrationIdOrNull(ReserveSeatResult result) {
        return switch (result) {
            case ReserveSeatResult.Reserved reserved -> reserved.id().value();
            case ReserveSeatResult.Confirmed confirmed -> confirmed.id().value();
            case ReserveSeatResult.SoldOut soldOut -> null;
            case ReserveSeatResult.NotOpenForRegistration closed -> null;
            case ReserveSeatResult.AlreadyRegistered already -> null;
        };
    }
}
