package com.unihub.payment.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.in.ChargePaymentUseCase;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.ShortChainITSupport;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.security.Actor;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * M-76 end to end, on the real flow through the real queues (Phase 3 is the first phase in which
 * this branch can happen at all): a paid hold; the charge is left {@code PENDING} at the gateway;
 * the hold runs out and is released by the delay queue and the expiry consumer; then the gateway
 * reports the payment succeeded. Registration refuses to confirm an expired hold, Payment moves to
 * {@code REFUND_REQUIRED}, writes the refund job in the same transaction, and the refund consumer
 * returns the money. The registration stays {@code EXPIRED}, the freed seat is not given back to
 * the student who lost it, and the gateway is asked for exactly one refund.
 *
 * <p>The relay is off, so the test is the relay, step by step: each message goes to the broker only
 * after the outbox row that holds it has been read (see {@link ShortChainITSupport}).
 */
// The listeners run in this context, so it is closed when the class ends.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class RefundEndToEndIT extends ShortChainITSupport {
    private static final Duration LONG_ENOUGH = Duration.ofSeconds(40);

    @Autowired private ChargePaymentUseCase chargePayment;
    @Autowired private ReconcilePaymentUseCase reconcile;

    private void awaitRows(String routingKey, UUID aggregate) {
        await().atMost(LONG_ENOUGH).until(() -> !outboxPayloads(aggregate, routingKey).isEmpty());
    }

    @Test
    @DisplayName(
            "paid hold -> charge PENDING -> hold expires -> late success -> rejection -> REFUND_REQUIRED -> REFUNDED, once")
    void lateSuccessAfterTheHoldExpired_isRefundedExactlyOnce() {
        var workshop = aWorkshop(3);
        var student = fixtures.aStudent();
        var held = reserveFor(student, workshop);
        // The gateway is still working on the charge: PENDING, with its gateway id.
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
        var payment = charged.paymentId();
        var registration = held.id().value();

        // The hold runs out, by itself: the delay queue, then the expiry consumer.
        relayHold(registration);
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> assertThat(registrationStatus(registration)).isEqualTo("EXPIRED"));
        assertThat(fixtures.seatsTaken(workshop)).isZero();
        // Someone else takes the freed seat.
        var successor = reserveForNewStudent(workshop);
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);

        // The gateway reports success after all: PENDING -> SUCCEEDED, with charge.succeeded.
        when(gateway.retrieve(charged.gatewayTransactionId()))
                .thenReturn(GatewayPaymentState.SUCCEEDED);
        reconcile.reconcile(new ReconcilePaymentCommand(PaymentId.of(payment)));
        assertThat(paymentStatus(payment)).isEqualTo("SUCCEEDED");

        // Registration refuses to re-grant the seat and rejects the confirmation...
        assertThat(relay("unihub.payment.charge.succeeded", payment)).isEqualTo(1);
        awaitRows("unihub.registration.confirmation.rejected", registration);
        assertThat(registrationStatus(registration)).isEqualTo("EXPIRED");

        // ...Payment takes the money to REFUND_REQUIRED and writes the refund job with it...
        relay("unihub.registration.confirmation.rejected", registration);
        await().atMost(LONG_ENOUGH)
                .untilAsserted(
                        () -> assertThat(paymentStatus(payment)).isEqualTo("REFUND_REQUIRED"));
        awaitRows("unihub.payment.refund.requested", payment);
        assertThat(outboxRows(payment, "unihub.payment.refund.requested")).isEqualTo(1);

        // ...and the refund consumer returns it.
        when(gateway.findRefund(charged.gatewayTransactionId())).thenReturn(Optional.empty());
        when(gateway.refund(PaymentId.of(payment), charged.gatewayTransactionId()))
                .thenReturn(GatewayRefundState.SUCCEEDED);
        relay("unihub.payment.refund.requested", payment);
        await().atMost(LONG_ENOUGH)
                .untilAsserted(() -> assertThat(paymentStatus(payment)).isEqualTo("REFUNDED"));

        assertThat(registrationStatus(registration))
                .as("the seat is never re-granted")
                .isEqualTo("EXPIRED");
        assertThat(registrationStatus(successor.id().value())).isEqualTo("RESERVED");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertSeatsMatchHolders(workshop);
        verify(gateway, times(1)).refund(any(), any());
        assertThat(parkedMessages()).isZero();
    }
}
