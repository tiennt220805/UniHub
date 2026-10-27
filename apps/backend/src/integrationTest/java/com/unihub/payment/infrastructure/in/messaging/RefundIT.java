package com.unihub.payment.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.in.RequireRefundUseCase;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayCircuitOpenException;
import com.unihub.payment.application.port.out.GatewayRefundState;
import com.unihub.registration.ShortChainITSupport;
import com.unihub.shared.domain.id.PaymentId;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * M-76, DR-PAY-2 (payment.md §9, checklist D25, AC25.1-25.6): the refund consumer on its own queue,
 * through the real broker, the real listener and the real shared error handler (the retry chain is
 * 0.3 s a stage, {@link ShortChainITSupport}). Only the gateway is doubled: what it is asked, and
 * what each answer does to the payment and to the message.
 */
// The listeners run in this context, so it is closed when the class ends.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class RefundIT extends ShortChainITSupport {
    private static final String REFUND_REQUESTED = "unihub.payment.refund.requested";
    private static final String QUEUE = RefundRequestedListener.QUEUE;
    private static final Duration LONG_ENOUGH = Duration.ofSeconds(30);

    @Autowired private RequireRefundUseCase requireRefund;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void gatewayHoldsNoRefundYet() {
        Metrics.addRegistry(meters);
        when(gateway.findRefund(any())).thenReturn(Optional.empty());
        when(gateway.refund(any(), any())).thenReturn(GatewayRefundState.SUCCEEDED);
    }

    @AfterEach
    void detachMeters() {
        Metrics.removeRegistry(meters);
    }

    /** A payment owed back, as the registration's rejection leaves it, with its refund job. */
    private Paid anOwedPayment() {
        var paid = aPaidRegistration();
        closeTheRegistration(paid.registration(), "EXPIRED");
        requireRefund.requireRefund(new RequireRefundCommand(PaymentId.of(paid.payment())));
        assertThat(paymentStatus(paid.payment())).isEqualTo("REFUND_REQUIRED");
        assertThat(outboxRows(paid.payment(), REFUND_REQUESTED)).isEqualTo(1);
        return paid;
    }

    private void awaitPayment(UUID payment, String status) {
        await().atMost(LONG_ENOUGH)
                .untilAsserted(() -> assertThat(paymentStatus(payment)).isEqualTo(status));
    }

    private double parkedByRefund() {
        var counter = meters.find("unihub.payment.refund.parked").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName(
            "refundRequired_callsGatewayWithDeterministicKey_thenRefunded: one refund, then REFUNDED")
    void refundRequired_callsGateway_thenRefunded() {
        var paid = anOwedPayment();

        relay(REFUND_REQUESTED, paid.payment());

        awaitPayment(paid.payment(), "REFUNDED");
        // listed first, then created for this payment (the adapter turns the id into the key)
        verify(gateway).findRefund(paid.gatewayId());
        verify(gateway).refund(PaymentId.of(paid.payment()), paid.gatewayId());
        assertThat(parkedMessages()).isZero();
        assertThat(parkedByRefund()).isZero();
    }

    @Test
    @DisplayName(
            "reprocessWhileRefundRequired_callsRefundAgain: a failed call leaves the payment owed and the chain retries")
    void reprocessWhileRefundRequired_callsRefundAgain() {
        var paid = anOwedPayment();
        var attempts = new AtomicInteger();
        when(gateway.refund(any(), any()))
                .thenAnswer(
                        call -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new GatewayCallFailedException("blip", null);
                            }
                            return GatewayRefundState.SUCCEEDED;
                        });

        relay(REFUND_REQUESTED, paid.payment());

        awaitPayment(paid.payment(), "REFUNDED");
        verify(gateway, times(2)).refund(any(), any());
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName(
            "refunded_isNoOp: a redelivered job for a REFUNDED payment never touches the gateway")
    void refunded_isNoOp() throws Exception {
        var paid = anOwedPayment();
        relay(REFUND_REQUESTED, paid.payment());
        awaitPayment(paid.payment(), "REFUNDED");
        var version = paymentVersion(paid.payment());

        relay(REFUND_REQUESTED, paid.payment());
        relay(REFUND_REQUESTED, paid.payment());
        await().atMost(Duration.ofSeconds(10)).until(() -> waiting(QUEUE) == 0);
        Thread.sleep(500);

        assertThat(paymentStatus(paid.payment())).isEqualTo("REFUNDED");
        assertThat(paymentVersion(paid.payment())).isEqualTo(version);
        verify(gateway, times(1)).refund(any(), any());
        verify(gateway, times(1)).findRefund(any());
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName("a refund the gateway already holds is used, not created again (AC25.2)")
    void existingRefund_isNotCreatedAgain() {
        var paid = anOwedPayment();
        when(gateway.findRefund(paid.gatewayId()))
                .thenReturn(Optional.of(GatewayRefundState.SUCCEEDED));

        relay(REFUND_REQUESTED, paid.payment());

        awaitPayment(paid.payment(), "REFUNDED");
        verify(gateway, never()).refund(any(), any());
    }

    @Test
    @DisplayName(
            "pending keeps the payment REFUND_REQUIRED; a later succeeded makes it REFUNDED (AC25.3)")
    void pending_thenSucceeded() {
        var paid = anOwedPayment();
        var asked = new AtomicInteger();
        when(gateway.findRefund(paid.gatewayId()))
                .thenAnswer(
                        call ->
                                Optional.of(
                                        asked.incrementAndGet() < 3
                                                ? GatewayRefundState.PENDING
                                                : GatewayRefundState.SUCCEEDED));

        relay(REFUND_REQUESTED, paid.payment());

        awaitPayment(paid.payment(), "REFUNDED");
        assertThat(asked.get()).isEqualTo(3);
        verify(gateway, never()).refund(any(), any());
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName(
            "still pending after the chain's three retries: parked, payment stays owed (AC25.4)")
    void pendingForever_isParkedAfterThree() {
        var paid = anOwedPayment();
        when(gateway.findRefund(paid.gatewayId()))
                .thenReturn(Optional.of(GatewayRefundState.PENDING));

        relay(REFUND_REQUESTED, paid.payment());

        await().atMost(LONG_ENOUGH).until(() -> parkedMessages() == 1);
        assertThat(paymentStatus(paid.payment())).isEqualTo("REFUND_REQUIRED");
        verify(gateway, times(4)).findRefund(paid.gatewayId());
        assertThat(parkedByRefund()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("failed or cancelled at the gateway: parked at once, payment stays owed (AC25.4)")
    void failedAtTheGateway_isParkedAtOnce() {
        var paid = anOwedPayment();
        when(gateway.findRefund(paid.gatewayId()))
                .thenReturn(Optional.of(GatewayRefundState.FAILED));

        relay(REFUND_REQUESTED, paid.payment());

        await().atMost(LONG_ENOUGH).until(() -> parkedMessages() == 1);
        assertThat(paymentStatus(paid.payment())).isEqualTo("REFUND_REQUIRED");
        verify(gateway, times(1)).findRefund(paid.gatewayId());
        assertThat(parkedByRefund()).isEqualTo(1.0);
        // the longest-waiting refund is visible to an operator
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments WHERE id = ? AND status ="
                                        + " 'REFUND_REQUIRED'",
                                Integer.class,
                                paid.payment()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "gateway never reached (circuit open) for far longer than the chain: not parked, then refunded (AC25.5)")
    void circuitOpen_neverParks_thenRefunds() throws Exception {
        var paid = anOwedPayment();
        doThrow(new GatewayCircuitOpenException("the payment circuit is open", null))
                .when(gateway)
                .findRefund(any());

        relay(REFUND_REQUESTED, paid.payment());

        // the chain is 3 x 0.3 s: wait for many more attempts than three
        await().atMost(LONG_ENOUGH)
                .untilAsserted(() -> verify(gateway, atLeast(7)).findRefund(any()));
        assertThat(paymentStatus(paid.payment())).isEqualTo("REFUND_REQUIRED");
        assertThat(parkedMessages()).as("never parked").isZero();
        verify(gateway, never()).refund(any(), any());

        doReturn(Optional.empty()).when(gateway).findRefund(any());
        awaitPayment(paid.payment(), "REFUNDED");
        verify(gateway, times(1)).refund(eq(PaymentId.of(paid.payment())), any());
        assertThat(parkedMessages()).isZero();
    }

    @Test
    @DisplayName("any other gateway failure is parked after three retries, not retried forever")
    void ordinaryFailure_isParkedAfterThree() {
        var paid = anOwedPayment();
        when(gateway.findRefund(any())).thenThrow(new GatewayCallFailedException("5xx", null));

        relay(REFUND_REQUESTED, paid.payment());

        await().atMost(LONG_ENOUGH).until(() -> parkedMessages() == 1);
        assertThat(paymentStatus(paid.payment())).isEqualTo("REFUND_REQUIRED");
        verify(gateway, times(4)).findRefund(any());
    }
}
