package com.unihub.payment.infrastructure.in.messaging;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.payment.application.port.in.RequireRefundUseCase;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayPaymentState;
import com.unihub.payment.application.result.ChargePaymentResult;
import com.unihub.registration.HandOffITSupport;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.infrastructure.Redeliveries;
import com.unihub.shared.security.Actor;
import com.unihub.shared.web.RequestFingerprint;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * INV-11, the concurrent half for the Payment consumers that run on the shared gateway double
 * (Phase 4 checklist D41, AC41.1, M-40): the same message handled by two threads at once through
 * the {@code port/in} its listener calls, the overlap measured, the effects counted. The sequential
 * half is {@code PaymentCompensationIT} (rejection → {@code REFUND_REQUIRED}) and {@code
 * ReconciliationRequestedListenerIT} (three deliveries of one request). The refund consumer, whose
 * proof is the idempotency key the gateway sees, is {@code RefundConcurrentRedeliveryIT}.
 *
 * <p>A losing call may fail only with the version race, which the broker's retry chain absorbs;
 * after a failure the call is made once more, standing for the retry hop, and must change nothing.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class PaymentConsumersConcurrentRedeliveryIT extends HandOffITSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ROUNDS = 5;

    @Autowired private RequireRefundUseCase requireRefund;
    @Autowired private ReconcilePaymentUseCase reconcile;

    /** A charge the gateway is still working on: PENDING, with its gateway id. */
    private ChargePaymentResult.Processed aPendingPayment() {
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var workshopId = fixtures.track(workshop.id());
        var actor = new Actor.UserActor(fixtures.aStudent(), Set.of());
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
        return processed;
    }

    private UUID registrationOf(UUID payment) {
        return jdbc.queryForObject(
                "SELECT registration_id FROM payments WHERE id = ?", UUID.class, payment);
    }

    @Test
    @DisplayName(
            "payment-refund: the same rejection handled by two threads at once takes the payment to"
                    + " REFUND_REQUIRED once, with one refund job")
    void sameRejection_fromTwoThreads_requiresTheRefundOnce() {
        for (int round = 0; round < ROUNDS; round++) {
            var paid = aPaidRegistration();
            closeTheRegistration(paid.registration(), "EXPIRED");
            var versionBefore = paymentVersion(paid.payment());
            var command = new RequireRefundCommand(PaymentId.of(paid.payment()));

            var outcome =
                    Redeliveries.concurrently(2, () -> requireRefund.requireRefund(command))
                            .assertOnlyFailuresOf(OptimisticConflictException.class);
            if (outcome.anyFailed()) {
                requireRefund.requireRefund(command); // the retry hop: must be a no-op
            }

            assertThat(paymentStatus(paid.payment()))
                    .as("round " + round)
                    .isEqualTo("REFUND_REQUIRED");
            assertThat(paymentVersion(paid.payment()))
                    .as("one conditional transition")
                    .isEqualTo(versionBefore + 1);
            assertThat(outboxRows(paid.payment(), "unihub.payment.refund.requested"))
                    .as("one transition, one refund job")
                    .isEqualTo(1);
        }
        verify(gateway, never()).refund(any(), any());
    }

    @Test
    @DisplayName(
            "reconciliation: the same request handled by two threads at once resolves the payment"
                    + " once and announces the success once")
    void sameReconciliation_fromTwoThreads_resolvesOnce() {
        for (int round = 0; round < ROUNDS; round++) {
            var stuck = aPendingPayment();
            // The second caller is held at the gateway long enough for the first to commit, so it
            // reaches the resolution step after the row has changed: the in-transaction re-read of
            // the status, not only the version race, is what it meets.
            var asked = new AtomicInteger();
            when(gateway.retrieve(stuck.gatewayTransactionId()))
                    .thenAnswer(
                            call -> {
                                if (asked.incrementAndGet() == 2) {
                                    Thread.sleep(400);
                                }
                                return GatewayPaymentState.SUCCEEDED;
                            });
            var versionBefore = paymentVersion(stuck.paymentId());
            var command = new ReconcilePaymentCommand(PaymentId.of(stuck.paymentId()));

            var outcome =
                    Redeliveries.concurrently(2, () -> reconcile.reconcile(command))
                            .assertOnlyFailuresOf(OptimisticConflictException.class);
            if (outcome.anyFailed()) {
                reconcile.reconcile(command); // the retry hop: must be a no-op
            }

            assertThat(paymentStatus(stuck.paymentId()))
                    .as("round " + round)
                    .isEqualTo("SUCCEEDED");
            assertThat(paymentVersion(stuck.paymentId()))
                    .as("one conditional transition")
                    .isEqualTo(versionBefore + 1);
            assertThat(fixtures.paymentOutboxRows(registrationOf(stuck.paymentId())))
                    .as("one charge.succeeded")
                    .isEqualTo(1);
        }
    }
}
