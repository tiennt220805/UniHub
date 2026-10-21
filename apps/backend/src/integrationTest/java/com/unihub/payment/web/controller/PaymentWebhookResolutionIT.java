package com.unihub.payment.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.shared.security.Actor;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What a verified webhook does to a payment (payment.md §7, §9; checklist D2, D5, AC5.5, G2): it
 * resolves a PENDING one exactly once, is a no-op on a redelivery, turns a late success into a
 * refund and never into a confirmation, and closes the idempotency key a crashed charge left open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentWebhookResolutionIT extends PaymentWebhookITSupport {

    private int post200(String body) {
        return post(body, sign(body)).getStatusCode().value();
    }

    @Test
    @DisplayName("a redelivered success resolves once: one SUCCEEDED, one charge.succeeded row")
    void redelivery_isNoOp() {
        var pending = aPendingPayment();
        var body = eventBody("payment_intent.succeeded", pending.gatewayId());

        assertThat(post200(body)).isEqualTo(200);
        assertThat(post200(body)).isEqualTo(200);
        assertThat(post200(eventBody("payment_intent.succeeded", pending.gatewayId())))
                .isEqualTo(200);

        assertThat(paymentStatus(pending.payment())).isEqualTo("SUCCEEDED");
        assertThat(fixtures.paymentOutboxRows(pending.registration())).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed payment_intent resolves the payment FAILED, with no event")
    void failure_resolvesFailed() {
        var pending = aPendingPayment();

        assertThat(post200(eventBody("payment_intent.payment_failed", pending.gatewayId())))
                .isEqualTo(200);

        assertThat(paymentStatus(pending.payment())).isEqualTo("FAILED");
        assertThat(fixtures.paymentOutboxRows(pending.registration())).isZero();
    }

    @Test
    @DisplayName("a transaction no payment has is acknowledged with 200 and changes nothing")
    void unknownTransaction_is200() {
        var pending = aPendingPayment();

        assertThat(post200(eventBody("payment_intent.succeeded", "pi_never_initiated")))
                .isEqualTo(200);

        assertThat(paymentStatus(pending.payment())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("an event about anything else is acknowledged and ignored")
    void unrelatedEvent_is200() {
        var pending = aPendingPayment();

        assertThat(post200(eventBody("charge.refunded", pending.gatewayId()))).isEqualTo(200);

        assertThat(paymentStatus(pending.payment())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName(
            "a success reported for a payment already FAILED goes straight to REFUND_REQUIRED, never SUCCEEDED")
    void lateSuccess_goesToRefundRequired_noChargeSucceeded() {
        var pending = aPendingPayment();
        post200(eventBody("payment_intent.payment_failed", pending.gatewayId()));

        assertThat(post200(eventBody("payment_intent.succeeded", pending.gatewayId())))
                .isEqualTo(200);
        assertThat(post200(eventBody("payment_intent.succeeded", pending.gatewayId())))
                .as("and a redelivery of the late success changes nothing")
                .isEqualTo(200);

        assertThat(paymentStatus(pending.payment())).isEqualTo("REFUND_REQUIRED");
        assertThat(fixtures.paymentOutboxRows(pending.registration()))
                .as("no charge.succeeded, so no registration can be confirmed by it")
                .isZero();
    }

    @Test
    @DisplayName(
            "a late success for a CANCELLED payment is also a refund, even when another payment of the registration succeeded")
    void lateSuccess_doesNotViolateTheLiveIndex() {
        var first = aPendingPayment();
        post200(eventBody("payment_intent.canceled", first.gatewayId()));
        assertThat(paymentStatus(first.payment())).isEqualTo("CANCELLED");
        // a second attempt for the same registration succeeds meanwhile
        var secondCharge =
                (ChargePaymentResult.Processed)
                        chargePayment.charge(
                                new ChargePaymentCommand(
                                        new Actor.UserActor(first.student(), Set.of()),
                                        com.unihub.shared.domain.id.RegistrationId.of(
                                                first.registration()),
                                        "pm_card_visa",
                                        java.util.UUID.randomUUID().toString(),
                                        "fingerprint-2"));
        post200(eventBody("payment_intent.succeeded", secondCharge.gatewayTransactionId()));

        assertThat(post200(eventBody("payment_intent.succeeded", first.gatewayId())))
                .isEqualTo(200);

        assertThat(paymentStatus(first.payment())).isEqualTo("REFUND_REQUIRED");
        assertThat(paymentStatus(secondCharge.paymentId())).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName(
            "a charge that crashed before tx2 left its key open: the webhook completes it, and a same-key retry replays")
    void crashedCharge_keyIsCompletedByTheWebhook() {
        // a charge whose tx2 cannot complete the key, as a crash between the transactions
        var held = newCrashedCharge();
        assertThat(fixtures.chargeKeyStatus(held.student())).isEqualTo("IN_PROGRESS");

        assertThat(post200(eventBody("payment_intent.succeeded", held.gatewayId()))).isEqualTo(200);

        assertThat(fixtures.chargeKeyStatus(held.student())).isEqualTo("COMPLETED");
        var retry =
                (ChargePaymentResult.Processed)
                        chargePayment.charge(
                                new ChargePaymentCommand(
                                        new Actor.UserActor(held.student(), Set.of()),
                                        com.unihub.shared.domain.id.RegistrationId.of(
                                                held.registration()),
                                        "pm_card_visa",
                                        held.key().toString(),
                                        "fingerprint"));
        assertThat(retry.status()).isEqualTo(ChargePaymentResult.Status.SUCCEEDED);
    }

    /** Charges, but makes tx2 fail, as a crash between the transactions would. */
    private Pending newCrashedCharge() {
        var workshop =
                com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop()
                        .withCapacity(5)
                        .build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var workshopId = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var actor = new Actor.UserActor(student, Set.of());
        var reserved =
                (com.unihub.registration.application.result.ReserveSeatResult.Reserved)
                        reserveSeat.reserve(
                                new com.unihub.registration.application.command.ReserveSeatCommand(
                                        actor,
                                        workshopId,
                                        java.util.UUID.randomUUID().toString(),
                                        "f1"));
        var key = java.util.UUID.randomUUID();
        tx.executeWithoutResult(
                s ->
                        doThrow(new CrashBeforeTx2())
                                .when(idempotency)
                                .complete(any(), any(), any(), any(), any()));
        try {
            chargePayment.charge(
                    new ChargePaymentCommand(
                            actor, reserved.id(), "pm_card_visa", key.toString(), "fingerprint"));
        } catch (CrashBeforeTx2 expected) {
            // tx2 rolled back: the payment is PENDING with its gateway id, the key IN_PROGRESS
        }
        reset(idempotency);
        var gatewayId =
                jdbc.queryForObject(
                        "SELECT gateway_transaction_id FROM payments WHERE registration_id = ?",
                        String.class,
                        reserved.id().value());
        var paymentId =
                jdbc.queryForObject(
                        "SELECT id FROM payments WHERE registration_id = ?",
                        java.util.UUID.class,
                        reserved.id().value());
        return new Pending(student, reserved.id().value(), paymentId, gatewayId, key);
    }

    /** Not an IllegalStateException: a repository proxy would translate that one. */
    private static final class CrashBeforeTx2 extends RuntimeException {
        CrashBeforeTx2() {
            super("crash between the transactions");
        }
    }
}
