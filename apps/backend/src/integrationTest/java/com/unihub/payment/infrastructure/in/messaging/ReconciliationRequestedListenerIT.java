package com.unihub.payment.infrastructure.in.messaging;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.port.in.RequestPaymentReconciliationsUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayCallFailedException;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.HandOffITSupport;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.security.Actor;
import com.unihub.shared.web.RequestFingerprint;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * M-23 through the broker and the real listener (payment.md §6; checklist AC2.5): the sweep writes
 * a request for a stuck payment, the request is delivered like the relay would deliver it, and the
 * listener resolves the payment from the gateway's record — with no webhook anywhere. A request
 * delivered again changes nothing, and a gateway that cannot be asked is retried by the broker
 * until it can.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class ReconciliationRequestedListenerIT extends HandOffITSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUESTED = "unihub.payment.reconciliation.requested";

    @Autowired private RequestPaymentReconciliationsUseCase sweep;

    @AfterEach
    void removeTheRequests() {
        jdbc.update("DELETE FROM event_publications WHERE routing_key = ?", REQUESTED);
    }

    /** A payment the gateway never settled, 20 minutes old, with its request already written. */
    private Paid aStuckPaymentWithItsRequest() {
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var workshopId = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var actor = new Actor.UserActor(student, Set.of());
        var reserved =
                (ReserveSeatResult.Reserved)
                        reserveSeat.reserve(
                                new ReserveSeatCommand(
                                        actor,
                                        workshopId,
                                        UUID.randomUUID().toString(),
                                        RequestFingerprint.of(
                                                JSON, Map.of("workshopId", workshopId.value()))));
        var processed =
                (ChargePaymentResult.Processed)
                        chargePayment.charge(
                                new ChargePaymentCommand(
                                        actor,
                                        reserved.id(),
                                        "pm_card_visa",
                                        UUID.randomUUID().toString(),
                                        "fingerprint"));
        assertThat(processed.status()).isEqualTo(ChargePaymentResult.Status.PENDING);
        fixtures.backdatePayment(processed.paymentId(), Duration.ofMinutes(20));
        sweep.requestReconciliations();
        assertThat(fixtures.reconciliationRequests(processed.paymentId())).isEqualTo(1);
        return new Paid(
                student,
                workshopId.value(),
                reserved.id().value(),
                processed.paymentId(),
                processed.gatewayTransactionId());
    }

    /** What the relay would publish for the payment's request. */
    private void relay(Paid stuck) {
        for (var payload : outboxPayloads(stuck.payment(), REQUESTED)) {
            publish(REQUESTED, payload);
        }
    }

    private void waitUntilIdle() throws InterruptedException {
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                amqpAdmin
                                                .getQueueInfo(ReconciliationRequestedListener.QUEUE)
                                                .getMessageCount()
                                        == 0);
        // the last delivery may still be in the listener; give it the time a transaction takes
        Thread.sleep(500);
    }

    @Test
    @DisplayName("M-23: a stuck payment is resolved from the gateway by the request alone, once")
    void request_resolvesTheStuckPayment_withoutAWebhook() throws Exception {
        var stuck = aStuckPaymentWithItsRequest();
        when(gateway.retrieve(stuck.gatewayId())).thenReturn(GatewayPaymentState.SUCCEEDED);
        var versionBefore = paymentVersion(stuck.payment());

        // delivered three times: a sweep that ran twice, a broker that redelivers
        relay(stuck);
        relay(stuck);
        relay(stuck);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(paymentStatus(stuck.payment())).isEqualTo("SUCCEEDED"));
        waitUntilIdle();
        assertThat(paymentVersion(stuck.payment()))
                .as("one conditional transition, however many requests")
                .isEqualTo(versionBefore + 1);
        assertThat(fixtures.paymentOutboxRows(stuck.registration()))
                .as("one charge.succeeded")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a gateway that cannot be asked is retried through the broker until it answers")
    void gatewayDown_isRetriedByTheBroker() {
        var stuck = aStuckPaymentWithItsRequest();
        when(gateway.retrieve(stuck.gatewayId()))
                .thenThrow(new GatewayCallFailedException("down", null))
                .thenReturn(GatewayPaymentState.SUCCEEDED);

        relay(stuck);

        // the first delivery fails and leaves the payment alone; the 5 s retry stage brings it back
        await().atMost(Duration.ofSeconds(40))
                .untilAsserted(
                        () -> assertThat(paymentStatus(stuck.payment())).isEqualTo("SUCCEEDED"));
        verify(gateway, times(2)).retrieve(stuck.gatewayId());
    }
}
