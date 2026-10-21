package com.unihub.registration.infrastructure.in.messaging;

import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.port.in.ConfirmRegistrationUseCase;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Registration's side of "payment succeeded" (payment.md §9, messaging.md §2). The message type is
 * this module's own reading of the wire contract, not Payment's class: only the JSON is shared. The
 * listener maps and delegates; it never catches, so a failure reaches the shared error handler
 * (backend-coding-standards.md §8.5-§8.6).
 */
@Component
class PaymentConfirmationListener {
    static final String QUEUE = "unihub.payment-confirmation.queue";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The fields of {@code unihub.payment.charge.succeeded} this module reads. */
    record PaymentSucceededMessage(
            UUID paymentId, UUID registrationId, Instant occurredAt, Integer schemaVersion) {}

    private final ConfirmRegistrationUseCase confirmRegistration;

    PaymentConfirmationListener(ConfirmRegistrationUseCase confirmRegistration) {
        this.confirmRegistration = confirmRegistration;
    }

    @RabbitListener(queues = QUEUE)
    void onPaymentSucceeded(PaymentSucceededMessage message) {
        if (message.schemaVersion() == null
                || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException(
                    "unsupported schemaVersion " + message.schemaVersion());
        }
        if (message.paymentId() == null || message.registrationId() == null) {
            throw new NonRetryableMessageException("payment.charge.succeeded without its ids");
        }
        confirmRegistration.confirm(
                new ConfirmRegistrationCommand(
                        PaymentId.of(message.paymentId()),
                        RegistrationId.of(message.registrationId())));
    }
}
