package com.unihub.registration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.registration.domain.model.TicketType;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.api.SeatClaimOutcome;
import com.unihub.workshop.api.SeatInventory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReserveSeatTransactionTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");
    private static final RegistrationPolicy POLICY =
            new RegistrationPolicy(
                    Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofSeconds(5), 3);
    private static final WorkshopId WORKSHOP = WorkshopId.newId();
    private static final UserId STUDENT = UserId.newId();
    private static final ReserveSeatCommand COMMAND =
            new ReserveSeatCommand(new Actor.UserActor(STUDENT, Set.of()), WORKSHOP);

    private final SeatInventory seats = mock(SeatInventory.class);
    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final ReserveSeatTransaction transaction =
            new ReserveSeatTransaction(
                    seats, registrations, POLICY, Clock.fixed(NOW, ZoneOffset.UTC));

    private Registration savedRegistration() {
        var captor = ArgumentCaptor.forClass(Registration.class);
        verify(registrations).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a paid claim saves a RESERVED registration held for the policy's duration")
    void paidClaim_reservesTheSeat() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(true));

        var result = transaction.execute(COMMAND);

        var saved = savedRegistration();
        assertThat(saved.status()).isEqualTo(RegistrationStatus.RESERVED);
        assertThat(saved.ticketType()).isEqualTo(TicketType.PAID);
        assertThat(saved.userId()).isEqualTo(STUDENT);
        assertThat(saved.workshopId()).isEqualTo(WORKSHOP);
        assertThat(saved.holdExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(result)
                .isEqualTo(new ReserveSeatResult.Reserved(saved.id(), saved.holdExpiresAt()));
    }

    @Test
    @DisplayName("a free claim saves a CONFIRMED registration with no hold")
    void freeClaim_confirmsAtOnce() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(false));

        var result = transaction.execute(COMMAND);

        var saved = savedRegistration();
        assertThat(saved.status()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(saved.ticketType()).isEqualTo(TicketType.FREE);
        assertThat(saved.holdExpiresAt()).isNull();
        assertThat(result).isEqualTo(new ReserveSeatResult.Confirmed(saved.id()));
    }

    @Test
    @DisplayName("a sold-out workshop saves nothing")
    void soldOut_savesNothing() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.SoldOut());

        assertThat(transaction.execute(COMMAND)).isEqualTo(new ReserveSeatResult.SoldOut());

        verify(registrations, never()).save(any());
    }

    @Test
    @DisplayName("a workshop that is not open saves nothing")
    void notOpen_savesNothing() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.NotOpenForRegistration());

        assertThat(transaction.execute(COMMAND))
                .isEqualTo(new ReserveSeatResult.NotOpenForRegistration());

        verify(registrations, never()).save(any());
    }

    @Test
    @DisplayName("a student who already holds a seat is told so before any seat is claimed")
    void alreadyRegistered_claimsNothing() {
        when(registrations.existsActive(WORKSHOP, STUDENT)).thenReturn(true);

        assertThat(transaction.execute(COMMAND))
                .isEqualTo(new ReserveSeatResult.AlreadyRegistered());

        verify(seats, never()).claim(any());
        verify(registrations, never()).save(any());
    }
}
