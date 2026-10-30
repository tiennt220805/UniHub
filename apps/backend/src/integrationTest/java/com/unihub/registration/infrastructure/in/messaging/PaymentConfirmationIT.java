package com.unihub.registration.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.unihub.registration.HandOffITSupport;
import com.unihub.registration.TicketITSupport;
import com.unihub.registration.api.TicketVerificationKey;
import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.port.in.ConfirmRegistrationUseCase;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.domain.exception.InvalidRegistrationStateException;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.SeatInventory;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Registration's half of the hand-off from a successful payment (checklist D2, T-CONF, AC2.1-2.4),
 * through the real broker and the real listener: a RESERVED registration is confirmed together with
 * its {@code ticket.confirmed} row; a redelivery changes nothing; one that was already expired or
 * cancelled is never re-granted but rejected; and confirm racing the expiry has exactly one winner.
 */
// The listeners run in this context, so it is closed when the class ends.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class PaymentConfirmationIT extends HandOffITSupport {
    private static final String TICKET_CONFIRMED = "unihub.registration.ticket.confirmed";
    private static final String REJECTED = "unihub.registration.confirmation.rejected";

    @Autowired private ConfirmRegistrationUseCase confirmRegistration;
    @Autowired private RegistrationRepository registrations;
    @Autowired private SeatInventory seatInventory;
    @Autowired private TicketVerificationKey verificationKey;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.unihub.shared.application.port.out.EventPublisher events;

    private final ExecutorService threads = Executors.newFixedThreadPool(4);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    private void waitUntilTheQueueIsIdle() throws InterruptedException {
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                amqpAdmin
                                                .getQueueInfo(PaymentConfirmationListener.QUEUE)
                                                .getMessageCount()
                                        == 0);
        // the last delivery may still be in the listener; give it the time a transaction takes
        Thread.sleep(500);
    }

    @Test
    @DisplayName(
            "a payment that succeeded confirms the RESERVED registration, with ticket.confirmed, seats unchanged")
    void succeededWhileReserved_confirmsWithTicketOutbox() throws Exception {
        var paid = aPaidRegistration();
        assertThat(TicketITSupport.storedTicket(jdbc, paid.registration()).code())
                .as("a RESERVED registration has no ticket")
                .isNull();
        var workshop = new WorkshopId(paid.workshop());
        var seatsBefore = fixtures.seatsTaken(workshop);

        publishChargeSucceeded(paid);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(registrationStatus(paid.registration()))
                                        .isEqualTo("CONFIRMED"));
        assertThat(outboxRows(paid.registration(), TICKET_CONFIRMED)).isEqualTo(1);
        assertThat(outboxRows(paid.registration(), REJECTED)).isZero();
        // AC48.1, paid path: status, ticket_code and qr_payload came from one UPDATE, and the
        // published key verifies the ticket
        TicketITSupport.assertVerifies(
                TicketITSupport.storedTicket(jdbc, paid.registration()),
                verificationKey.current(),
                paid.workshop());
        assertThat(fixtures.seatsTaken(workshop))
                .as("the seat was claimed at RESERVED; confirming takes no second one")
                .isEqualTo(seatsBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT hold_expires_at IS NULL FROM registrations WHERE id = ?",
                                Boolean.class,
                                paid.registration()))
                .isTrue();
    }

    @Test
    @DisplayName(
            "M-25, paid path: a failure after the ticket.confirmed insert leaves the registration RESERVED and no row")
    void confirmFailsAfterTheOutboxInsert_leavesNeither() {
        var paid = aPaidRegistration();
        // Stubbed in a transaction: the port is MANDATORY and the spy sits behind that proxy.
        tx.executeWithoutResult(
                s ->
                        org.mockito.Mockito.doThrow(new AfterTheInsert())
                                .when(events)
                                .publish(org.mockito.ArgumentMatchers.any()));
        try {
            assertThatThrownBy(
                            () ->
                                    confirmRegistration.confirm(
                                            new ConfirmRegistrationCommand(
                                                    PaymentId.of(paid.payment()),
                                                    RegistrationId.of(paid.registration()))))
                    .isInstanceOf(AfterTheInsert.class);

            assertThat(registrationStatus(paid.registration())).isEqualTo("RESERVED");
            assertThat(outboxRows(paid.registration(), TICKET_CONFIRMED)).isZero();
        } finally {
            org.mockito.Mockito.reset(events);
        }
    }

    /** Not an IllegalStateException: a repository proxy would translate that one. */
    private static final class AfterTheInsert extends RuntimeException {
        AfterTheInsert() {
            super("fails after the outbox row was inserted");
        }
    }

    @Test
    @DisplayName(
            "AC48.4: the same success delivered three times confirms once, announces once and never"
                    + " signs again")
    void redelivery_isNoOp() throws Exception {
        var paid = aPaidRegistration();

        publishChargeSucceeded(paid);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(registrationStatus(paid.registration()))
                                        .isEqualTo("CONFIRMED"));
        var issued = TicketITSupport.storedTicket(jdbc, paid.registration());
        publishChargeSucceeded(paid);
        publishChargeSucceeded(paid);
        waitUntilTheQueueIsIdle();

        assertThat(registrationStatus(paid.registration())).isEqualTo("CONFIRMED");
        assertThat(outboxRows(paid.registration(), TICKET_CONFIRMED)).isEqualTo(1);
        assertThat(outboxRows(paid.registration(), REJECTED)).isZero();
        assertThat(TicketITSupport.storedTicket(jdbc, paid.registration()))
                .as("the ticket is the one first issued: a redelivery signs nothing")
                .isEqualTo(issued);
    }

    @Test
    @DisplayName(
            "a success after the hold expired is rejected, not re-granted; the gateway is not called again")
    void succeededAfterExpired_emitsRejection() {
        var paid = aPaidRegistration();
        closeTheRegistration(paid.registration(), "EXPIRED");

        publishChargeSucceeded(paid);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(outboxRows(paid.registration(), REJECTED)).isEqualTo(1));
        assertThat(registrationStatus(paid.registration())).isEqualTo("EXPIRED");
        assertThat(outboxRows(paid.registration(), TICKET_CONFIRMED)).isZero();
        assertThat(TicketITSupport.storedTicket(jdbc, paid.registration()).code())
                .as("a hold that lapsed is never granted a ticket")
                .isNull();
        assertThat(outboxPayloads(paid.registration(), REJECTED).get(0))
                .contains("HOLD_EXPIRED")
                .contains(paid.payment().toString());
        verify(gateway, times(1)).confirm(any(), any());
    }

    @Test
    @DisplayName("a success after the registration was cancelled is rejected with its own reason")
    void succeededAfterCancelled_emitsRejection() {
        var paid = aPaidRegistration();
        closeTheRegistration(paid.registration(), "CANCELLED");

        publishChargeSucceeded(paid);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(outboxRows(paid.registration(), REJECTED)).isEqualTo(1));
        assertThat(registrationStatus(paid.registration())).isEqualTo("CANCELLED");
        assertThat(TicketITSupport.storedTicket(jdbc, paid.registration()).code()).isNull();
        assertThat(outboxPayloads(paid.registration(), REJECTED).get(0))
                .contains("REGISTRATION_CANCELLED");
    }

    @Test
    @DisplayName(
            "confirm racing the expiry: exactly one wins, and the seat count agrees with the winner")
    void raceConfirmVsExpire_exactlyOneWins() throws Exception {
        for (int round = 0; round < 8; round++) {
            var paid = aPaidRegistration();
            var workshop = new WorkshopId(paid.workshop());
            var start = new CountDownLatch(1);
            var confirm =
                    CompletableFuture.runAsync(
                            () -> {
                                waitFor(start);
                                confirmRegistration.confirm(
                                        new ConfirmRegistrationCommand(
                                                PaymentId.of(paid.payment()),
                                                RegistrationId.of(paid.registration())));
                            },
                            threads);
            var expire =
                    CompletableFuture.supplyAsync(
                            () -> {
                                waitFor(start);
                                return expire(paid.registration(), workshop);
                            },
                            threads);
            start.countDown();
            confirm.get(30, TimeUnit.SECONDS);
            var expired = expire.get(30, TimeUnit.SECONDS);

            var status = registrationStatus(paid.registration());
            var tickets = outboxRows(paid.registration(), TICKET_CONFIRMED);
            var rejections = outboxRows(paid.registration(), REJECTED);
            var seats = fixtures.seatsTaken(workshop);
            if (expired) {
                assertThat(status).as("round " + round).isEqualTo("EXPIRED");
                assertThat(tickets).as("round " + round).isZero();
                assertThat(rejections).as("round " + round).isEqualTo(1);
                assertThat(seats).as("the expired hold gave its seat back").isZero();
            } else {
                assertThat(status).as("round " + round).isEqualTo("CONFIRMED");
                assertThat(tickets).as("round " + round).isEqualTo(1);
                assertThat(rejections).as("round " + round).isZero();
                assertThat(seats).as("the confirmed seat stays taken").isEqualTo(1);
            }
        }
    }

    /** The Phase 3 expiry's transaction: a conditional EXPIRED transition and the seat release. */
    private boolean expire(UUID registration, WorkshopId workshop) {
        try {
            tx.executeWithoutResult(
                    s -> {
                        var loaded =
                                registrations
                                        .findById(RegistrationId.of(registration))
                                        .orElseThrow();
                        loaded.expire();
                        registrations.transition(loaded, RegistrationStatus.RESERVED);
                        seatInventory.release(workshop);
                    });
            return true;
        } catch (OptimisticConflictException
                | DataAccessException
                | InvalidRegistrationStateException lost) {
            // the confirmation got there first: the conditional update matched no row
            return false;
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
