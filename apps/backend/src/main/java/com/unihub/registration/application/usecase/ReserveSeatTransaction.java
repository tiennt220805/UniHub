package com.unihub.registration.application.usecase;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.model.Registration;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.workshop.api.SeatClaimOutcome;
import com.unihub.workshop.api.SeatInventory;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at the seat reservation, in one transaction: the seat claim (Workshop, {@code
 * MANDATORY}) and the {@code registrations} insert commit together or not at all
 * (database-schema.md §4.1), and the audit entry is recorded here (it takes effect after commit).
 * The idempotency record and the outbox row join this transaction with their own checklist items
 * (3.3, 3.4).
 */
@Service
class ReserveSeatTransaction {
    private final SeatInventory seatInventory;
    private final RegistrationRepository registrations;
    private final AuditTrail auditTrail;
    private final RegistrationPolicy policy;
    private final Clock clock;

    ReserveSeatTransaction(
            SeatInventory seatInventory,
            RegistrationRepository registrations,
            AuditTrail auditTrail,
            RegistrationPolicy policy,
            Clock clock) {
        this.seatInventory = seatInventory;
        this.registrations = registrations;
        this.auditTrail = auditTrail;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public ReserveSeatResult execute(ReserveSeatCommand command) {
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
                                : Registration.confirmFree(id, workshopId, userId);
                // The saved aggregate carries the row's timestamps, which the response needs.
                var saved = registrations.save(registration);
                // Deferred to after commit and fail-open (AuditTrail): never blocks the claim.
                auditTrail.record(
                        AuditEntry.of(
                                command.actor(),
                                AuditActions.REGISTRATION_CREATED,
                                AuditTargets.REGISTRATION,
                                id.value(),
                                now));
                yield saved.isConfirmed()
                        ? new ReserveSeatResult.Confirmed(id, saved.createdAt(), saved.updatedAt())
                        : new ReserveSeatResult.Reserved(
                                id, saved.holdExpiresAt(), saved.createdAt(), saved.updatedAt());
            }
        };
    }
}
