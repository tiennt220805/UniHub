package com.unihub.registration.application.usecase;

import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.domain.exception.HoldNotYetExpiredException;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.messaging.NonRetryableMessageException;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.api.SeatInventory;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at releasing an expired hold, in one transaction at the Master. The registration is
 * read first and decides: {@code RESERVED} past its {@code hold_expires_at} becomes {@code EXPIRED}
 * by a conditional update and its seat goes back in the same transaction, so a seat is never
 * returned without the registration leaving {@code RESERVED} (checklist C5, database-schema.md
 * §4.1); any other status was resolved by another path and is a no-op; a hold with time left is not
 * acted on (checklist D27). The message is never trusted for any of this.
 *
 * <p>Losing the conditional update to the payment confirmation, or the {@code workshops} update to
 * an organizer edit, is an {@code OptimisticConflictException} for the caller to retry; both writes
 * roll back together.
 */
@Service
class ExpireSeatHoldTransaction {

    static final String EXPIRY_PROCESS = "seat-hold-expiry";

    enum Effect {
        EXPIRED,
        NO_OP
    }

    private final RegistrationRepository registrations;
    private final SeatInventory seatInventory;
    private final AuditTrail auditTrail;
    private final Clock clock;

    ExpireSeatHoldTransaction(
            RegistrationRepository registrations,
            SeatInventory seatInventory,
            AuditTrail auditTrail,
            Clock clock) {
        this.registrations = registrations;
        this.seatInventory = seatInventory;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Transactional
    Effect attempt(ExpireSeatHoldCommand command) {
        var registration =
                registrations
                        .findById(command.registrationId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "seat-hold expiry for unknown registration "
                                                        + command.registrationId().value()));
        // The lock taken was the message's. If it is not this registration's workshop, the hold
        // was not released under the right lock, and no retry will make the message right.
        if (!registration.workshopId().equals(command.workshopId())) {
            throw new NonRetryableMessageException(
                    "registration "
                            + registration.id().value()
                            + " does not belong to workshop "
                            + command.workshopId().value());
        }
        if (registration.status() != RegistrationStatus.RESERVED) {
            return Effect.NO_OP;
        }
        var now = clock.instant();
        if (registration.holdExpiresAt().isAfter(now)) {
            throw new HoldNotYetExpiredException(registration.id(), registration.holdExpiresAt());
        }
        registration.expire();
        registrations.transition(registration, RegistrationStatus.RESERVED);
        seatInventory.release(registration.workshopId());
        auditTrail.record(
                AuditEntry.of(
                        Actor.system(EXPIRY_PROCESS),
                        AuditActions.REGISTRATION_EXPIRED,
                        AuditTargets.REGISTRATION,
                        registration.id().value(),
                        now));
        return Effect.EXPIRED;
    }
}
