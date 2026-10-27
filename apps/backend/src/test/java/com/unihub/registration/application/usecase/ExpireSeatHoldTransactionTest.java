package com.unihub.registration.application.usecase;

import static com.unihub.registration.domain.model.RegistrationBuilder.HOLD_UNTIL;
import static com.unihub.registration.domain.model.RegistrationBuilder.aCancelledRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aConfirmedRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.anExpiredRegistration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.usecase.ExpireSeatHoldTransaction.Effect;
import com.unihub.registration.domain.exception.HoldNotYetExpiredException;
import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.messaging.NonRetryableMessageException;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.api.SeatInventory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The state table of the hold expiry (registration.md §3, checklist D27, C5, AC27.1-27.3): what the
 * consumer does to a registration in each status, and that the seat goes back together with the
 * transition, never alone.
 */
class ExpireSeatHoldTransactionTest {
    private static final Instant DUE = HOLD_UNTIL;

    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final SeatInventory seatInventory = mock(SeatInventory.class);
    private final AuditTrail audit = mock(AuditTrail.class);

    private ExpireSeatHoldTransaction at(Instant now) {
        return new ExpireSeatHoldTransaction(
                registrations, seatInventory, audit, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ExpireSeatHoldCommand found(Registration registration) {
        when(registrations.findById(registration.id())).thenReturn(Optional.of(registration));
        return new ExpireSeatHoldCommand(registration.id(), registration.workshopId());
    }

    @Test
    @DisplayName("a RESERVED hold at its time is EXPIRED by the conditional transition, seat back")
    void reservedAtItsTime_isExpiredAndTheSeatReturned() {
        var registration = aReservedRegistration().build();

        var effect = at(DUE).attempt(found(registration));

        assertThat(effect).isEqualTo(Effect.EXPIRED);
        assertThat(registration.status()).isEqualTo(RegistrationStatus.EXPIRED);
        var order = inOrder(registrations, seatInventory);
        order.verify(registrations).transition(registration, RegistrationStatus.RESERVED);
        order.verify(seatInventory).release(registration.workshopId());
    }

    @Test
    @DisplayName("a hold long past its time is expired just the same")
    void reservedLongAfter_isExpired() {
        var registration = aReservedRegistration().build();

        assertThat(at(DUE.plus(Duration.ofHours(3))).attempt(found(registration)))
                .isEqualTo(Effect.EXPIRED);
    }

    @Test
    @DisplayName("a hold with time left is not acted on: a transient failure for the 5s stage")
    void reservedBeforeItsTime_isNotActedOn() {
        var registration = aReservedRegistration().build();
        var command = found(registration);

        assertThatThrownBy(() -> at(DUE.minusMillis(1)).attempt(command))
                .isInstanceOf(HoldNotYetExpiredException.class);

        assertThat(registration.status()).isEqualTo(RegistrationStatus.RESERVED);
        verify(registrations, never()).transition(any(), any());
        verifyNoInteractions(seatInventory, audit);
    }

    @Test
    @DisplayName("CONFIRMED, EXPIRED and CANCELLED are resolved already: nothing changes")
    void otherStatuses_areNoOps() {
        for (var registration :
                new Registration[] {
                    aConfirmedRegistration().build(),
                    anExpiredRegistration().build(),
                    aCancelledRegistration().build()
                }) {
            var before = registration.status();

            assertThat(at(DUE.plus(Duration.ofDays(1))).attempt(found(registration)))
                    .as(before.name())
                    .isEqualTo(Effect.NO_OP);
            assertThat(registration.status()).isEqualTo(before);
        }
        verify(registrations, never()).transition(any(), any());
        verifyNoInteractions(seatInventory, audit);
    }

    @Test
    @DisplayName("losing the conditional update (payment confirmed first) returns no seat")
    void lostTransition_returnsNoSeat() {
        var registration = aReservedRegistration().build();
        var command = found(registration);
        doThrow(new OptimisticConflictException("REGISTRATION", registration.id().value()))
                .when(registrations)
                .transition(any(), any());

        assertThatThrownBy(() -> at(DUE).attempt(command))
                .isInstanceOf(OptimisticConflictException.class);

        verifyNoInteractions(seatInventory, audit);
    }

    @Test
    @DisplayName("losing the workshops update (organizer edit) is a conflict; nothing is audited")
    void lostSeatRelease_isAConflictAndNothingIsAudited() {
        var registration = aReservedRegistration().build();
        var command = found(registration);
        doThrow(new OptimisticConflictException("WORKSHOP", registration.workshopId().value()))
                .when(seatInventory)
                .release(registration.workshopId());

        assertThatThrownBy(() -> at(DUE).attempt(command))
                .isInstanceOf(OptimisticConflictException.class);

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a message for another workshop than the registration's is permanent")
    void workshopMismatch_isNonRetryable() {
        var registration = aReservedRegistration().build();
        when(registrations.findById(registration.id())).thenReturn(Optional.of(registration));

        assertThatThrownBy(
                        () ->
                                at(DUE).attempt(
                                                new ExpireSeatHoldCommand(
                                                        registration.id(), WorkshopId.newId())))
                .isInstanceOf(NonRetryableMessageException.class);

        verify(registrations, never()).transition(any(), any());
        verifyNoInteractions(seatInventory);
    }

    @Test
    @DisplayName("an unknown registration is a failure, never a silent success")
    void unknownRegistration_fails() {
        var registration = aReservedRegistration().build();
        when(registrations.findById(registration.id())).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                at(DUE).attempt(
                                                new ExpireSeatHoldCommand(
                                                        registration.id(),
                                                        registration.workshopId())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the expiry is audited as the seat-hold-expiry process, not as a user")
    void expiry_isAuditedAsTheProcess() {
        var registration = aReservedRegistration().build();

        at(DUE).attempt(found(registration));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().actor()).isEqualTo(Actor.system("seat-hold-expiry"));
        assertThat(entry.getValue().action()).isEqualTo(AuditActions.REGISTRATION_EXPIRED);
        assertThat(entry.getValue().targetType()).isEqualTo(AuditTargets.REGISTRATION);
        assertThat(entry.getValue().targetId()).isEqualTo(registration.id().value());
    }
}
