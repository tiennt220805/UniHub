package com.unihub.registration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.registration.api.event.RegistrationConfirmedIntegrationEvent;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.event.SeatHoldExpiryRequested;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.registration.domain.model.TicketType;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyRecord;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.port.out.StoredResult;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.api.SeatClaimOutcome;
import com.unihub.workshop.api.SeatInventory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class ReserveSeatTransactionTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant STORED_AT = Instant.parse("2026-12-01T09:00:00.123Z");
    private static final RegistrationPolicy POLICY =
            new RegistrationPolicy(
                    Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofSeconds(5), 3);
    private static final String KEY = UUID.randomUUID().toString();
    private static final String FINGERPRINT = "a".repeat(64);
    private static final WorkshopId WORKSHOP = WorkshopId.newId();
    private static final UserId STUDENT = UserId.newId();
    private static final ReserveSeatCommand COMMAND =
            new ReserveSeatCommand(
                    new Actor.UserActor(STUDENT, Set.of()), WORKSHOP, KEY, FINGERPRINT);

    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final SeatInventory seats = mock(SeatInventory.class);
    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ReserveSeatTransaction transaction =
            new ReserveSeatTransaction(
                    idempotency,
                    seats,
                    registrations,
                    auditTrail,
                    events,
                    POLICY,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    /** The repository returns the aggregate as stored: same state, plus the row's timestamps. */
    @BeforeEach
    void saveStampsTimestamps() {
        when(registrations.save(any()))
                .thenAnswer(
                        invocation -> {
                            Registration r = invocation.getArgument(0);
                            return Registration.restore(
                                    r.id(),
                                    r.workshopId(),
                                    r.userId(),
                                    r.ticketType(),
                                    r.status(),
                                    r.holdExpiresAt(),
                                    r.ticketCode(),
                                    r.qrPayload(),
                                    r.version(),
                                    STORED_AT,
                                    STORED_AT);
                        });
    }

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
                .isEqualTo(
                        new ReserveSeatResult.Reserved(
                                saved.id(), saved.holdExpiresAt(), STORED_AT, STORED_AT));
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
        assertThat(result)
                .isEqualTo(new ReserveSeatResult.Confirmed(saved.id(), STORED_AT, STORED_AT));
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

    @Test
    @DisplayName("a created registration is audited with the caller as actor, once")
    void created_isAudited() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(false));

        transaction.execute(COMMAND);

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditTrail).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditActions.REGISTRATION_CREATED);
        assertThat(entry.getValue().actor()).isEqualTo(COMMAND.actor());
        assertThat(entry.getValue().targetId()).isEqualTo(savedRegistration().id().value());
        assertThat(entry.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a refusal creates nothing, so nothing is audited")
    void refusal_isNotAudited() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.SoldOut());

        transaction.execute(COMMAND);

        verify(auditTrail, never()).record(any());
    }

    // ---------- outbox: the ticket.confirmed row is written by this transaction (INV-10)
    // ----------

    @Test
    @DisplayName("a free claim publishes exactly one ticket.confirmed message for the saved row")
    void freeClaim_publishesTicketConfirmed() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(false));

        transaction.execute(COMMAND);

        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(events).publish(message.capture());
        assertThat(message.getValue())
                .isEqualTo(
                        new RegistrationConfirmedIntegrationEvent(
                                savedRegistration().id().value(),
                                WORKSHOP.value(),
                                STUDENT.value(),
                                NOW));
    }

    @Test
    @DisplayName("the message is written after the registration and before the key completes")
    void publish_afterSave_beforeComplete() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(false));

        transaction.execute(COMMAND);

        InOrder order = Mockito.inOrder(registrations, events, idempotency);
        order.verify(registrations).save(any());
        order.verify(events).publish(any());
        order.verify(idempotency).complete(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a paid hold publishes the hold-expiry job, and no confirmation")
    void paidClaim_publishesTheHoldExpiryJob() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(true));

        transaction.execute(COMMAND);

        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(events).publish(message.capture());
        assertThat(message.getValue())
                .isEqualTo(
                        new SeatHoldExpiryRequested(
                                savedRegistration().id().value(),
                                WORKSHOP.value(),
                                NOW.plus(Duration.ofMinutes(15)),
                                NOW));
    }

    @Test
    @DisplayName("a refusal publishes nothing")
    void refusals_publishNothing() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.SoldOut());
        transaction.execute(COMMAND);
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.NotOpenForRegistration());
        transaction.execute(COMMAND);
        when(registrations.existsActive(WORKSHOP, STUDENT)).thenReturn(true);
        transaction.execute(COMMAND);

        verify(events, never()).publish(any());
    }

    // ---------- idempotency: begin first, complete last, every decided outcome recorded ----------

    private static final IdempotencyScope SCOPE = IdempotencyScope.REGISTRATION_CREATE;

    @Test
    @DisplayName(
            "the key is begun before anything else is written and completed last, with the Result")
    void begin_first_complete_last() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(false));

        var result = transaction.execute(COMMAND);

        InOrder order = Mockito.inOrder(idempotency, registrations, seats);
        order.verify(idempotency).begin(SCOPE, STUDENT, KEY, FINGERPRINT, null);
        order.verify(registrations).existsActive(WORKSHOP, STUDENT);
        order.verify(seats).claim(WORKSHOP);
        order.verify(registrations).save(any());
        order.verify(idempotency)
                .complete(
                        eq(SCOPE),
                        eq(STUDENT),
                        eq(KEY),
                        eq(result),
                        eq(((ReserveSeatResult.Confirmed) result).id().value()));
    }

    @Test
    @DisplayName("a rejection is recorded too, so a retry replays it instead of re-deciding")
    void rejections_areRecorded() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.SoldOut());
        var soldOut = transaction.execute(COMMAND);
        verify(idempotency).complete(SCOPE, STUDENT, KEY, soldOut, null);

        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.NotOpenForRegistration());
        var notOpen = transaction.execute(COMMAND);
        verify(idempotency).complete(SCOPE, STUDENT, KEY, notOpen, null);

        when(registrations.existsActive(WORKSHOP, STUDENT)).thenReturn(true);
        var already = transaction.execute(COMMAND);
        verify(idempotency).complete(SCOPE, STUDENT, KEY, already, null);
    }

    @Test
    @DisplayName("a paid hold is recorded with its registration id as the resource")
    void paid_isRecordedWithItsId() {
        when(seats.claim(WORKSHOP)).thenReturn(new SeatClaimOutcome.Claimed(true));

        var result = (ReserveSeatResult.Reserved) transaction.execute(COMMAND);

        verify(idempotency).complete(SCOPE, STUDENT, KEY, result, result.id().value());
    }

    @Test
    @DisplayName("a Result recorded meanwhile is replayed: nothing begun, claimed or saved")
    void recheck_replaysWithoutAnyEffect() {
        var stored = new StoredResult("SoldOut", "{}");
        when(idempotency.find(SCOPE, STUDENT, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord.Completed(FINGERPRINT, stored)));
        when(idempotency.decode(stored, ReserveSeatResult.class))
                .thenReturn(new ReserveSeatResult.SoldOut());

        assertThat(transaction.execute(COMMAND)).isEqualTo(new ReserveSeatResult.SoldOut());

        verify(idempotency, never()).begin(any(), any(), any(), any(), any());
        verify(seats, never()).claim(any());
        verify(registrations, never()).save(any());
    }

    @Test
    @DisplayName("the recheck refuses the same key with another body and a key still in progress")
    void recheck_refusals() {
        when(idempotency.find(SCOPE, STUDENT, KEY))
                .thenReturn(
                        Optional.of(
                                new IdempotencyRecord.Completed(
                                        "0".repeat(64), new StoredResult("SoldOut", "{}"))));
        assertThatThrownBy(() -> transaction.execute(COMMAND))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        when(idempotency.find(SCOPE, STUDENT, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord.InProgress(FINGERPRINT)));
        assertThatThrownBy(() -> transaction.execute(COMMAND))
                .isInstanceOf(IdempotencyKeyInUseException.class);

        verify(seats, never()).claim(any());
    }

    @Test
    @DisplayName("a failure while deciding propagates; nothing is completed (the key rolls back)")
    void failure_doesNotComplete() {
        when(seats.claim(WORKSHOP)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> transaction.execute(COMMAND))
                .isInstanceOf(IllegalStateException.class);

        verify(idempotency, never()).complete(any(), any(), any(), any(), any());
    }
}
