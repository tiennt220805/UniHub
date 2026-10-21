package com.unihub.payment.infrastructure.in.messaging;

import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.in.RequireRefundUseCase;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Payment's side of "the registration could not be confirmed" (payment.md §9, messaging.md §2). The
 * message type is this module's own reading of the wire contract. The listener maps and delegates;
 * it never catches (backend-coding-standards.md §8.5-§8.6).
 */
@Component
class ConfirmationRejectedListener {
    static final String QUEUE = "unihub.payment-refund.queue";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The fields of {@code unihub.registration.confirmation.rejected} this module reads. */
    record ConfirmationRejectedMessage(
            UUID paymentId, UUID registrationId, String reason, Integer schemaVersion) {}

    private final RequireRefundUseCase requireRefund;

    ConfirmationRejectedListener(RequireRefundUseCase requireRefund) {
        this.requireRefund = requireRefund;
    }

    @RabbitListener(queues = QUEUE)
    void onConfirmationRejected(ConfirmationRejectedMessage message) {
        if (message.schemaVersion() == null
                || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException(
                    "unsupported schemaVersion " + message.schemaVersion());
        }
        if (message.paymentId() == null) {
            throw new NonRetryableMessageException("confirmation.rejected without a payment id");
        }
        requireRefund.requireRefund(new RequireRefundCommand(PaymentId.of(message.paymentId())));
    }
}
