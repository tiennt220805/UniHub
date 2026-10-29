package com.unihub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.ShortChainITSupport;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.Mailpit;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.Actor;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-37, the paid branch (Phase 4 checklist 3.2), on the real flow through the real queues and the
 * real relay, with only the payment gateway doubled: {@code charge.succeeded} → Registration
 * confirms → {@code ticket.confirmed} → the notification. And its negative: a payment that succeeds
 * after the hold ran out is refunded and the student is <em>not</em> told they hold a seat
 * (Registration never confirms an expired hold, so {@code ticket.confirmed} is never written).
 *
 * <p>Runs on {@link ShortChainITSupport}'s private broker with a hold of seconds; its relay is
 * switched on here, so the test publishes nothing by hand. The context is closed when the class
 * ends because the relay and the listeners run.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class PaidRegistrationNotificationIT extends ShortChainITSupport {

    private static final Duration LONG_ENOUGH = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @Autowired private ReconcilePaymentUseCase reconcile;

    private final java.util.List<UUID> students = new java.util.ArrayList<>();

    @BeforeEach
    void noRowsFromEarlierTests() {
        jdbc.update("DELETE FROM event_publications");
    }

    /**
     * Runs before the superclass clean-up deletes the users and registrations these rows point at.
     *
     * <p>The live relay publishes a hold job for every reservation, also for one that was confirmed
     * a moment later. The broker is shared by every class built on {@link ShortChainITSupport}: a
     * job that fires after the registration is gone is parked as "unknown registration" and would
     * sit in the parking lot of the next class's assertions. So every job is let run its course
     * while its registration still exists.
     */
    @AfterEach
    void letTheHoldJobsRunAndForgetTheNotifications() {
        await().atMost(LONG_ENOUGH)
                .during(Duration.ofMillis(700))
                .until(
                        () ->
                                waiting(Topology.HOLD_DELAY_QUEUE) == 0
                                        && waiting("unihub.seat-hold-expiry.queue") == 0);
        students.forEach(user -> jdbc.update("DELETE FROM notifications WHERE user_id = ?", user));
        students.clear();
    }

    private String emailOf(UUID user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
    }

    private int notificationRows(UUID user, String channel, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND channel = ? AND status"
                        + " = ?",
                Integer.class,
                user,
                channel,
                status);
    }

    private int confirmationsPublished(UUID registration) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE aggregate_id = ? AND routing_key"
                        + " = 'unihub.registration.ticket.confirmed'",
                Integer.class,
                registration);
    }

    @Test
    @DisplayName(
            "a paid registration is confirmed by its payment, and then exactly one email and one"
                    + " in-app notification tell the student")
    void paidRegistration_isConfirmedByEmailAndInApp() {
        var paid = aPaidRegistration();
        students.add(paid.student().value());

        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> {
                            assertThat(registrationStatus(paid.registration()))
                                    .isEqualTo("CONFIRMED");
                            assertThat(notificationRows(paid.student().value(), "EMAIL", "SENT"))
                                    .isEqualTo(1);
                            assertThat(notificationRows(paid.student().value(), "IN_APP", "SENT"))
                                    .isEqualTo(1);
                        });

        var mails = Mailpit.to(emailOf(paid.student().value()));
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().text())
                .contains(paid.registration().toString())
                .contains("01/12/2026 16:00");
        assertThat(confirmationsPublished(paid.registration())).isEqualTo(1);
        assertThat(waiting("unihub.notification.queue")).isZero();
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName(
            "a payment that succeeds after the hold expired is refunded and nobody is told they"
                    + " hold a seat")
    void lateSuccess_isRefundedAndNothingIsSent() {
        var workshop = aWorkshop(3);
        UserId student = fixtures.aStudent();
        students.add(student.value());
        var held = reserveFor(student, workshop);
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
        var charged =
                (ChargePaymentResult.Processed)
                        chargePayment.charge(
                                new ChargePaymentCommand(
                                        new Actor.UserActor(student, Set.of()),
                                        held.id(),
                                        "pm_card_visa",
                                        UUID.randomUUID().toString(),
                                        "fingerprint"));
        assertThat(charged.status()).isEqualTo(ChargePaymentResult.Status.PENDING);
        var registration = held.id().value();

        // the hold runs out by itself: relay → delay queue → expiry consumer
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> assertThat(registrationStatus(registration)).isEqualTo("EXPIRED"));

        // then the gateway reports success after all; the refund is stubbed before it can be asked
        when(gateway.findRefund(charged.gatewayTransactionId())).thenReturn(Optional.empty());
        when(gateway.refund(PaymentId.of(charged.paymentId()), charged.gatewayTransactionId()))
                .thenReturn(GatewayRefundState.SUCCEEDED);
        when(gateway.retrieve(charged.gatewayTransactionId()))
                .thenReturn(GatewayPaymentState.SUCCEEDED);
        reconcile.reconcile(new ReconcilePaymentCommand(PaymentId.of(charged.paymentId())));

        // the positive barrier: the whole compensation ran, so nothing can still be on its way
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> assertThat(paymentStatus(charged.paymentId())).isEqualTo("REFUNDED"));

        assertThat(registrationStatus(registration)).isEqualTo("EXPIRED");
        assertThat(confirmationsPublished(registration))
                .as("Registration never announced a confirmation")
                .isZero();
        assertThat(notificationRows(student.value(), "EMAIL", "SENT")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE user_id = ?",
                                Integer.class,
                                student.value()))
                .isZero();
        assertThat(Mailpit.to(emailOf(student.value()))).isEmpty();
        assertThat(waiting("unihub.notification.queue")).isZero();
        assertThat(parkedMessages()).isZero();
        assertThat(Topology.retryStageQueues("unihub.notification.queue"))
                .allSatisfy(stage -> assertThat(waiting(stage)).isZero());
    }
}
