package com.unihub.registration.application.usecase;

import static com.unihub.registration.domain.model.RegistrationBuilder.aCancelledRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aConfirmedRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.aReservedRegistration;
import static com.unihub.registration.domain.model.RegistrationBuilder.anExpiredRegistration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.registration.api.event.RegistrationConfirmationRejectedIntegrationEvent;
import com.unihub.registration.api.event.RegistrationConfirmedIntegrationEvent;
import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.application.port.out.TicketSigner;
import com.unihub.registration.application.usecase.ConfirmRegistrationTransaction.Effect;
import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.registration.domain.model.TestTickets;
import com.unihub.registration.domain.model.Ticket;
import com.unihub.shared.application.port.out.AuditActions;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.event.OutboxMessage;
import com.unihub.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The branches of the hand-off from "payment succeeded" (payment.md §9, checklist D2, AC2.1-2.4).
 */
class ConfirmRegistrationTransactionTest {
    private static final Instant NOW = Instant.parse("2026-12-01T09:00:00Z");
    private static final PaymentId PAYMENT = PaymentId.newId();
    private static final Ticket TICKET = TestTickets.aTicket();

    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final TicketSigner signer = mock(TicketSigner.class);
    private final ConfirmRegistrationTransaction transaction =
            new ConfirmRegistrationTransaction(
                    registrations, events, audit, signer, Clock.fixed(NOW, ZoneOffset.UTC));

    @org.junit.jupiter.api.BeforeEach
    void signerIssuesATicket() {
        when(signer.issue(any(), any())).thenReturn(TICKET);
    }

    private ConfirmRegistrationCommand found(Registration registration) {
        when(registrations.findById(registration.id())).thenReturn(Optional.of(registration));
        return new ConfirmRegistrationCommand(PAYMENT, registration.id());
    }

    @Test
    @DisplayName(
            "RESERVED is confirmed by the conditional transition, with ticket.confirmed after it")
    void reserved_isConfirmed() {
        var registration = aReservedRegistration().build();
        when(signer.issue(registration.workshopId(), NOW)).thenReturn(TICKET);

        var effect = transaction.attempt(found(registration));

        assertThat(effect).isEqualTo(Effect.CONFIRMED);
        assertThat(registration.status()).isEqualTo(RegistrationStatus.CONFIRMED);
        // the ticket is on the aggregate the conditional UPDATE writes (checkin.md §4)
        assertThat(registration.ticketCode()).isEqualTo(TICKET.code());
        assertThat(registration.qrPayload()).isEqualTo(TICKET.qrPayload());
        var order = inOrder(registrations, events);
        order.verify(registrations).transition(registration, RegistrationStatus.RESERVED);
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        order.verify(events).publish(message.capture());
        assertThat(message.getValue())
                .isEqualTo(
                        new RegistrationConfirmedIntegrationEvent(
                                registration.id().value(),
                                registration.workshopId().value(),
                                registration.userId().value(),
                                NOW));
    }

    @Test
    @DisplayName("a signing failure fails the attempt: nothing is written, nothing is published")
    void signingFailure_writesNothing() {
        var registration = aReservedRegistration().build();
        when(signer.issue(registration.workshopId(), NOW))
                .thenThrow(new IllegalStateException("signing failed"));
        var command = found(registration);

        assertThatThrownBy(() -> transaction.attempt(command))
                .isInstanceOf(IllegalStateException.class);

        verify(registrations, never()).transition(any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName(
            "an already CONFIRMED registration is a redelivery: nothing changes, nothing is published")
    void confirmed_isNoOp() {
        var registration = aConfirmedRegistration().build();

        assertThat(transaction.attempt(found(registration))).isEqualTo(Effect.ALREADY_CONFIRMED);

        verify(registrations, never()).transition(any(), any());
        verifyNoInteractions(events);
        // AC48.4: a redelivery never signs again, so the ticket stays the one first issued
        verifyNoInteractions(signer);
    }

    @Test
    @DisplayName(
            "EXPIRED is never re-granted: a HOLD_EXPIRED rejection is published and the row is untouched")
    void expired_isRejected() {
        var registration = anExpiredRegistration().build();

        assertThat(transaction.attempt(found(registration))).isEqualTo(Effect.REJECTED);

        assertThat(registration.status()).isEqualTo(RegistrationStatus.EXPIRED);
        verify(registrations, never()).transition(any(), any());
        verifyNoInteractions(signer);
        verify(events)
                .publish(
                        new RegistrationConfirmationRejectedIntegrationEvent(
                                PAYMENT.value(),
                                registration.id().value(),
                                RegistrationConfirmationRejectedIntegrationEvent.HOLD_EXPIRED,
                                NOW));
    }

    @Test
    @DisplayName("CANCELLED is rejected with its own reason")
    void cancelled_isRejected() {
        var registration = aCancelledRegistration().build();

        assertThat(transaction.attempt(found(registration))).isEqualTo(Effect.REJECTED);

        verifyNoInteractions(signer);
        verify(events)
                .publish(
                        new RegistrationConfirmationRejectedIntegrationEvent(
                                PAYMENT.value(),
                                registration.id().value(),
                                RegistrationConfirmationRejectedIntegrationEvent
                                        .REGISTRATION_CANCELLED,
                                NOW));
    }

    @Test
    @DisplayName("a payment for a registration that does not exist is an error for the retry chain")
    void unknownRegistration_throws() {
        var command =
                new ConfirmRegistrationCommand(
                        PAYMENT, com.unihub.shared.domain.id.RegistrationId.newId());
        when(registrations.findById(command.registrationId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transaction.attempt(command))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- audit (checklist D6): the consumer confirms, a process and never a user ----------

    @Test
    @DisplayName(
            "audit: confirming a registration is recorded once, under the confirmation process")
    void audit_confirmed() {
        var registration = aReservedRegistration().build();

        transaction.attempt(found(registration));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditActions.REGISTRATION_CONFIRMED);
        assertThat(entry.getValue().targetType()).isEqualTo(AuditTargets.REGISTRATION);
        assertThat(entry.getValue().targetId()).isEqualTo(registration.id().value());
        assertThat(entry.getValue().occurredAt()).isEqualTo(NOW);
        assertThat(entry.getValue().metadata())
                .containsEntry("paymentId", PAYMENT.value().toString());
        assertThat(entry.getValue().actor())
                .isEqualTo(Actor.system("payment-confirmation"))
                .isInstanceOf(Actor.SystemActor.class);
    }

    @Test
    @DisplayName("audit: a redelivery and a rejection confirm nothing, so they record nothing")
    void audit_notConfirmed_recordsNothing() {
        transaction.attempt(found(aConfirmedRegistration().build()));
        transaction.attempt(found(anExpiredRegistration().build()));
        transaction.attempt(found(aCancelledRegistration().build()));

        verifyNoInteractions(audit);
    }
}
