package com.unihub.payment.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.in.RequestPaymentReconciliationsUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.ShortChainITSupport;
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
 * M-41, AC42.2, for the one message type whose whole path no other test runs with the relay on:
 * {@code reconciliation.requested}. The sweep commits a request for a stuck payment; the real relay
 * publishes it; the real reconciliation consumer asks the gateway and moves the payment, with no
 * webhook anywhere; the {@code charge.succeeded} that this writes goes through the relay in turn.
 * When it has all settled, no outbox row is {@code PENDING}.
 *
 * <p>The other seven types are shown the same way by the tests named in {@code
 * OutboxRoutingCoverageTest} (a table that fails if a type has no entry). {@code
 * ReconciliationRequestedListenerIT} covers this consumer too, but there the test plays the relay
 * by hand.
 *
 * <p>The assertion about the outcome is the payment's own progress, not the registration's: the
 * hold of this class's broker is four seconds, and whether the student's seat was still held when
 * the payment resolved is a race this test is not about.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class OutboxRelayEndToEndIT extends ShortChainITSupport {

    private static final Duration LONG_ENOUGH = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
    }

    @Autowired private RequestPaymentReconciliationsUseCase sweep;

    private final java.util.List<UUID> students = new java.util.ArrayList<>();

    @BeforeEach
    void noRowsFromEarlierTests() {
        // the live relay would publish whatever an earlier class left committed
        jdbc.update("DELETE FROM event_publications");
    }

    /**
     * Every hold job is let run its course while its registration still exists: the broker is
     * shared by the classes built on {@link ShortChainITSupport}, and a job that fires after the
     * registration is gone is parked as "unknown registration" in the next class's assertions (see
     * {@code PaidRegistrationNotificationIT}).
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

    private int outboxRows(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publications WHERE status = ?", Integer.class, status);
    }

    @Test
    @DisplayName(
            "AC42.2: a stuck payment is resolved by a request that travels through the real relay,"
                    + " and no outbox row is left PENDING")
    void staleSweep_reachesTheReconciliationConsumer() {
        var workshop = aWorkshop(3);
        var student = fixtures.aStudent();
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
        // the gateway has settled it by now; if the hold ran out first the money is returned
        when(gateway.retrieve(charged.gatewayTransactionId()))
                .thenReturn(GatewayPaymentState.SUCCEEDED);
        when(gateway.findRefund(charged.gatewayTransactionId())).thenReturn(Optional.empty());
        when(gateway.refund(any(), any())).thenReturn(GatewayRefundState.SUCCEEDED);
        fixtures.backdatePayment(charged.paymentId(), Duration.ofMinutes(20));

        assertThat(sweep.requestReconciliations())
                .as("this payment is the only one stale enough to be taken")
                .isEqualTo(1);
        assertThat(fixtures.reconciliationRequests(charged.paymentId())).isEqualTo(1);

        // the sweep wrote no more than the outbox row; everything after it is the system's own
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () ->
                                assertThat(fixtures.paymentOutboxRows(held.id().value()))
                                        .as("the charge.succeeded the reconciliation wrote")
                                        .isEqualTo(1));
        assertThat(paymentStatus(charged.paymentId())).isNotEqualTo("PENDING");

        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () ->
                                assertThat(outboxRows("PENDING"))
                                        .as("every outbox row was published, none stranded")
                                        .isZero());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM event_publications WHERE routing_key ="
                                        + " 'unihub.payment.reconciliation.requested' AND"
                                        + " aggregate_id = ?",
                                String.class,
                                charged.paymentId()))
                .isEqualTo("PUBLISHED");
        assertThat(waiting("unihub.reconciliation.queue")).isZero();
        assertThat(parkedMessages()).isZero();
    }
}
