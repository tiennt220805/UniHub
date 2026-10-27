package com.unihub.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.in.RequireRefundUseCase;
import com.unihub.registration.HandOffITSupport;
import com.unihub.shared.application.port.out.EventPublisher;
import com.unihub.shared.domain.id.PaymentId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The refund job commits with the move to {@code REFUND_REQUIRED}, or not at all (payment.md §9,
 * checklist D26, AC26.1-26.3, INV-10): the transition and its outbox row are one transaction on a
 * real database, so a rolled-back transition leaves neither, and a redelivered rejection leaves one
 * transition and one row.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class RequireRefundIT extends HandOffITSupport {
    private static final String REFUND_REQUESTED = "unihub.payment.refund.requested";

    @Autowired private RequireRefundUseCase requireRefund;

    /** Spied so a test can make the transaction fail on the outbox insert. */
    @SpyBean private EventPublisher eventPublisher;

    @AfterEach
    void unspy() {
        reset(eventPublisher);
    }

    @Test
    @DisplayName(
            "the rejection of a SUCCEEDED payment makes it REFUND_REQUIRED with exactly one refund job, however often it is redelivered")
    void redelivered_oneTransition_oneJob() {
        var paid = aPaidRegistration();
        closeTheRegistration(paid.registration(), "EXPIRED");
        var version = paymentVersion(paid.payment());

        for (int i = 0; i < 4; i++) {
            requireRefund.requireRefund(new RequireRefundCommand(PaymentId.of(paid.payment())));
        }

        assertThat(paymentStatus(paid.payment())).isEqualTo("REFUND_REQUIRED");
        assertThat(paymentVersion(paid.payment())).isEqualTo(version + 1);
        assertThat(outboxRows(paid.payment(), REFUND_REQUESTED)).isEqualTo(1);
        assertThat(outboxPayloads(paid.payment(), REFUND_REQUESTED).getFirst())
                .contains(paid.payment().toString())
                .contains("\"schemaVersion\": 1");
    }

    @Test
    @DisplayName(
            "a failure after the job was written rolls the transition back: no REFUND_REQUIRED, no job")
    void rollback_leavesNeitherTheTransitionNorTheJob() {
        var paid = aPaidRegistration();
        closeTheRegistration(paid.registration(), "EXPIRED");
        var version = paymentVersion(paid.payment());
        tx.executeWithoutResult(
                s ->
                        doThrow(new IllegalArgumentException("outbox insert failed"))
                                .when(eventPublisher)
                                .publish(any()));

        assertThatThrownBy(
                        () ->
                                requireRefund.requireRefund(
                                        new RequireRefundCommand(PaymentId.of(paid.payment()))))
                .hasMessageContaining("outbox insert failed");

        assertThat(paymentStatus(paid.payment())).isEqualTo("SUCCEEDED");
        assertThat(paymentVersion(paid.payment())).isEqualTo(version);
        assertThat(outboxRows(paid.payment(), REFUND_REQUESTED)).isZero();
    }
}
