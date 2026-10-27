package com.unihub.registration.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.unihub.registration.ShortChainITSupport;
import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.port.in.ConfirmRegistrationUseCase;
import com.unihub.registration.application.port.in.ExpireSeatHoldUseCase;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * M-33, INV-07 (registration.md §3, checklist D19, D27, C5, AC19.2, AC27.1-27.3): a paid hold is
 * released by itself, through the real delay queue, the real listener and the real shared error
 * handler, with no caller present. The broker is a private one whose delay queue is seconds long
 * and whose retry chain is a fraction of a second, both read from the production properties ({@link
 * ShortChainITSupport}).
 *
 * <p>After every release, redelivery and race, {@code seats_taken} is compared with the number of
 * {@code RESERVED} and {@code CONFIRMED} registrations: this is the first code that <em>lowers</em>
 * the counter, and a double release would quietly create a seat that does not exist, which the
 * {@code >= 0} CHECK would not catch while the workshop still has room (INV-06).
 */
// The listeners run in this context, so it is closed when the class ends.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class SeatHoldExpiryIT extends ShortChainITSupport {
    private static final Duration LONG_ENOUGH = Duration.ofSeconds(30);
    private static final String QUEUE = SeatHoldExpiryListener.QUEUE;

    @Autowired private ExpireSeatHoldUseCase expireSeatHold;
    @Autowired private ConfirmRegistrationUseCase confirmRegistration;

    private final ExecutorService threads = Executors.newFixedThreadPool(4);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    private void awaitStatus(UUID registration, String status) {
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> assertThat(registrationStatus(registration)).isEqualTo(status));
    }

    private void awaitQueueIdle() throws InterruptedException {
        await().atMost(Duration.ofSeconds(10)).until(() -> waiting(QUEUE) == 0);
        // the last delivery may still be in the listener; give it the time a transaction takes
        Thread.sleep(500);
    }

    @Test
    @DisplayName(
            "a paid hold is released by itself: EXPIRED, one seat back, and the seat sells again")
    void unpaidHold_isReleasedByTheDelayQueue_andTheSeatSellsAgain() {
        var workshop = aWorkshop(2);
        var held = reserveForNewStudent(workshop);
        var other = reserveForNewStudent(workshop);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(2);

        relayHold(held.id().value());

        awaitStatus(held.id().value(), "EXPIRED");
        assertThat(registrationStatus(other.id().value())).isEqualTo("RESERVED");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertSeatsMatchHolders(workshop);
        assertThat(parkedMessages()).isZero();
        // the freed seat is the workshop's to sell again
        var again = reserveForNewStudent(workshop);
        assertThat(registrationStatus(again.id().value())).isEqualTo("RESERVED");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(2);
        assertSeatsMatchHolders(workshop);
    }

    @Test
    @DisplayName("while a hold is being released, other registrations of the workshop go through")
    void expiry_doesNotBlockRegistration() {
        var workshop = aWorkshop(400);
        var held = reserveForNewStudent(workshop);
        relayHold(held.id().value());

        // register as fast as possible until the release has happened, across it
        var slowest = Duration.ZERO;
        var registered = 0;
        while (!registrationStatus(held.id().value()).equals("EXPIRED")) {
            var started = System.nanoTime();
            reserveForNewStudent(workshop);
            var took = Duration.ofNanos(System.nanoTime() - started);
            if (took.compareTo(slowest) > 0) {
                slowest = took;
            }
            registered++;
            assertThat(registered).as("registrations made while waiting").isLessThan(380);
        }

        assertThat(registered).isPositive();
        assertThat(slowest).as("the slowest registration").isLessThan(Duration.ofSeconds(2));
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(registered);
        assertSeatsMatchHolders(workshop);
    }

    @Test
    @DisplayName("the same hold.expired delivered again and again returns the seat exactly once")
    void redelivery_returnsTheSeatOnce() throws Exception {
        var workshop = aWorkshop(5);
        var held = reserveForNewStudent(workshop);
        var kept = reserveForNewStudent(workshop);
        relayHold(held.id().value());
        awaitStatus(held.id().value(), "EXPIRED");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            deliverHoldExpired(held.id().value());
            awaitQueueIdle();
            assertThat(fixtures.seatsTaken(workshop))
                    .as("after redelivery " + (i + 1))
                    .isEqualTo(1);
            assertSeatsMatchHolders(workshop);
        }
        // a doubled release would have taken the other student's seat away as well
        assertThat(registrationStatus(kept.id().value())).isEqualTo("RESERVED");
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName("a hold that was confirmed meanwhile is left alone: no-op, seat kept")
    void confirmedRegistration_isNotReleased() throws Exception {
        var workshop = aWorkshop(3);
        var held = reserveForNewStudent(workshop);
        confirmRegistration.confirm(new ConfirmRegistrationCommand(PaymentId.newId(), held.id()));
        assertThat(registrationStatus(held.id().value())).isEqualTo("CONFIRMED");

        // past its time: the registration is no longer RESERVED, so there is nothing to release
        Thread.sleep(HOLD.plusMillis(500).toMillis());
        deliverHoldExpired(held.id().value());
        awaitQueueIdle();

        assertThat(registrationStatus(held.id().value())).isEqualTo("CONFIRMED");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertSeatsMatchHolders(workshop);
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName("a message that arrives a little early is not acted on, and a retry releases it")
    void earlyMessage_isRetriedUntilTheHoldIsUp() throws Exception {
        var workshop = aWorkshop(2);
        var held = reserveForNewStudent(workshop);

        // Half a second before the hold is up, as if the pod's clock ran slow: the first attempts
        // find time left and fail transiently; the chain (0.3 s stages here, 5 s in production)
        // brings the message back after the deadline.
        Thread.sleep(HOLD.minusMillis(500).toMillis());
        deliverHoldExpired(held.id().value());

        awaitStatus(held.id().value(), "EXPIRED");
        assertThat(fixtures.seatsTaken(workshop)).isZero();
        assertSeatsMatchHolders(workshop);
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName("the payment confirmation racing the expiry has exactly one winner, seats exact")
    void confirmRacingExpiry_hasOneWinner() throws Exception {
        var rounds = 12;
        var workshop = aWorkshop(rounds + 2);
        var registrations = new ArrayList<RegistrationId>();
        for (int i = 0; i < rounds; i++) {
            registrations.add(reserveForNewStudent(workshop).id());
        }
        // every hold is up before the race starts, so the expiry is allowed to act
        Thread.sleep(HOLD.plusMillis(500).toMillis());

        for (var registration : registrations) {
            var start = new CyclicBarrier(2);
            var confirm =
                    threads.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                confirmRegistration.confirm(
                                        new ConfirmRegistrationCommand(
                                                PaymentId.newId(), registration));
                                return null;
                            });
            var expire =
                    threads.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                expireSeatHold.expire(
                                        new ExpireSeatHoldCommand(registration, workshop));
                                return null;
                            });
            confirm.get(30, TimeUnit.SECONDS);
            expire.get(30, TimeUnit.SECONDS);
        }

        var confirmed = 0;
        for (var registration : registrations) {
            var status = registrationStatus(registration.value());
            var rejections =
                    outboxRows(registration.value(), "unihub.registration.confirmation.rejected");
            switch (status) {
                case "CONFIRMED" -> {
                    confirmed++;
                    assertThat(rejections).as("a confirmed hold is not rejected").isZero();
                }
                case "EXPIRED" ->
                        assertThat(rejections)
                                .as("an expired hold with a payment is rejected for a refund")
                                .isEqualTo(1);
                default -> throw new AssertionError("unresolved: " + status);
            }
        }
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(confirmed);
        assertSeatsMatchHolders(workshop);
    }

    @Test
    @DisplayName(
            "a message for an unknown registration is retried three times, then parked (AC19.2)")
    void otherFailure_isParkedAfterThreeRetries() {
        var workshop = aWorkshop(1);
        publish(
                HOLD_EXPIRED,
                "{\"registrationId\":\""
                        + UUID.randomUUID()
                        + "\",\"workshopId\":\""
                        + workshop.value()
                        + "\",\"holdExpiresAt\":\"2026-12-01T09:15:00Z\","
                        + "\"occurredAt\":\"2026-12-01T09:00:00Z\",\"schemaVersion\":1}");

        await().atMost(LONG_ENOUGH).until(() -> parkedMessages() == 1);
        assertThat(waiting(QUEUE)).isZero();
    }

    @Test
    @DisplayName("a message with a schema version it does not know is parked at once")
    void unsupportedSchemaVersion_isParkedAtOnce() {
        publish(
                HOLD_EXPIRED,
                "{\"registrationId\":\""
                        + UUID.randomUUID()
                        + "\",\"workshopId\":\""
                        + UUID.randomUUID()
                        + "\",\"schemaVersion\":9}");

        await().atMost(Duration.ofSeconds(10)).until(() -> parkedMessages() == 1);
    }

    @Test
    @DisplayName(
            "a registration of another workshop than the message names is parked, seat untouched")
    void workshopMismatch_isParkedAndNothingChanges() {
        var workshop = aWorkshop(2);
        var otherWorkshop = aWorkshop(2);
        var held = reserveForNewStudent(workshop);
        publish(
                HOLD_EXPIRED,
                "{\"registrationId\":\""
                        + held.id().value()
                        + "\",\"workshopId\":\""
                        + otherWorkshop.value()
                        + "\",\"schemaVersion\":1}");

        await().atMost(Duration.ofSeconds(10)).until(() -> parkedMessages() == 1);
        assertThat(registrationStatus(held.id().value())).isEqualTo("RESERVED");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.seatsTaken(otherWorkshop)).isZero();
    }
}
