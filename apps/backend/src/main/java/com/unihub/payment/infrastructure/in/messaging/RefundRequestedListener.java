package com.unihub.payment.infrastructure.in.messaging;

import com.unihub.payment.application.command.RefundPaymentCommand;
import com.unihub.payment.application.port.in.RefundPaymentUseCase;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Payment's own refund task (payment.md §9, messaging.md §2): every move to {@code REFUND_REQUIRED}
 * wrote the message, this reads it. It is the only caller of the gateway refund. The listener maps
 * and delegates; it never catches (backend-coding-standards.md §8.5-§8.6), so a gateway that was
 * never reached keeps the message in its retry chain (messaging.md §4).
 */
@Component
class RefundRequestedListener {
    static final String QUEUE = "unihub.payment-refund-execution.queue";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The fields of {@code unihub.payment.refund.requested} this module reads. */
    record RefundRequestedMessage(UUID paymentId, Integer schemaVersion) {}

    private final RefundPaymentUseCase refundPayment;

    RefundRequestedListener(RefundPaymentUseCase refundPayment) {
        this.refundPayment = refundPayment;
    }

    @RabbitListener(queues = QUEUE)
    void onRefundRequested(RefundRequestedMessage message) {
        if (message.schemaVersion() == null
                || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException(
                    "unsupported schemaVersion " + message.schemaVersion());
        }
        if (message.paymentId() == null) {
            throw new NonRetryableMessageException("refund.requested without a payment id");
        }
        refundPayment.refund(new RefundPaymentCommand(PaymentId.of(message.paymentId())));
    }
}
