package com.unihub.payment.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.unihub.registration.HandOffITSupport;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The compensation branch of the hand-off, end to end through the broker and both real listeners
 * (checklist D2, AC2.4, AC2.4b, C11): a success for a registration that is no longer RESERVED makes
 * Registration reject it, and Payment moves to REFUND_REQUIRED exactly once however many times
 * either message is delivered, writing exactly one refund job with it. This consumer never calls
 * the gateway: the refund is the job's consumer (RefundIT).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class PaymentCompensationIT extends HandOffITSupport {
    private static final String REJECTED = "unihub.registration.confirmation.rejected";

    private void waitUntilIdle(String queue) throws InterruptedException {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> amqpAdmin.getQueueInfo(queue).getMessageCount() == 0);
        Thread.sleep(500);
    }

    @Test
    @DisplayName(
            "a redelivered success and its repeated rejection take the payment to REFUND_REQUIRED exactly once")
    void redeliveredSuccessAndRejection_refundRequiredExactlyOnce() throws Exception {
        var paid = aPaidRegistration();
        closeTheRegistration(paid.registration(), "EXPIRED");
        var versionBefore = paymentVersion(paid.payment());

        // the success is delivered three times: the registration is unchanged each time, so it
        // rejects each time, with a new event id (payment.md §9)
        publishChargeSucceeded(paid);
        publishChargeSucceeded(paid);
        publishChargeSucceeded(paid);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(outboxRows(paid.registration(), REJECTED)).isEqualTo(3));
        assertThat(paymentStatus(paid.payment()))
                .as("nothing told Payment yet")
                .isEqualTo("SUCCEEDED");

        // the test is the relay: every rejection row goes to the broker
        for (var payload : outboxPayloads(paid.registration(), REJECTED)) {
            publish(REJECTED, payload);
        }
        waitUntilIdle(ConfirmationRejectedListener.QUEUE);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(paymentStatus(paid.payment()))
                                        .isEqualTo("REFUND_REQUIRED"));

        assertThat(paymentVersion(paid.payment()))
                .as("one conditional transition, however many messages")
                .isEqualTo(versionBefore + 1);
        assertThat(registrationStatus(paid.registration())).isEqualTo("EXPIRED");
        assertThat(outboxRows(paid.payment(), "unihub.payment.refund.requested"))
                .as("one transition, one refund job (checklist D26, AC26.2)")
                .isEqualTo(1);
        // the one charge made its two calls; the refund hand-off made none
        verify(gateway, times(1)).create(any(), any(), any(), any());
        verify(gateway, times(1)).confirm(any(), any());
        verifyNoMoreInteractions(gateway);
    }
}
