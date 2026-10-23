package com.unihub.payment.infrastructure.in.messaging;

import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Payment's own reconciliation task (payment.md §6, messaging.md §2): the sweep wrote the message,
 * this reads it. The listener maps and delegates; it never catches (backend-coding-standards.md
 * §8.5-§8.6), so a gateway that cannot be asked takes the message through the retry chain.
 */
@Component
class ReconciliationRequestedListener {
    static final String QUEUE = "unihub.reconciliation.queue";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The fields of {@code unihub.payment.reconciliation.requested} this module reads. */
    record ReconciliationRequestedMessage(UUID paymentId, Integer schemaVersion) {}

    private final ReconcilePaymentUseCase reconcile;

    ReconciliationRequestedListener(ReconcilePaymentUseCase reconcile) {
        this.reconcile = reconcile;
    }

    @RabbitListener(queues = QUEUE)
    void onReconciliationRequested(ReconciliationRequestedMessage message) {
        if (message.schemaVersion() == null
                || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException(
                    "unsupported schemaVersion " + message.schemaVersion());
        }
        if (message.paymentId() == null) {
            throw new NonRetryableMessageException("reconciliation.requested without a payment id");
        }
        reconcile.reconcile(new ReconcilePaymentCommand(PaymentId.of(message.paymentId())));
    }
}
