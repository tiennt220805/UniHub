package com.unihub.payment.application.event;

import com.unihub.payment.api.event.PaymentSucceededIntegrationEvent;
import com.unihub.payment.domain.event.PaymentDomainEvent;
import com.unihub.payment.domain.event.PaymentSucceeded;
import com.unihub.payment.domain.event.RefundRequired;
import com.unihub.shared.event.OutboxMessage;
import java.util.List;

/**
 * Maps every Payment domain event to the outbox messages it produces (backend-coding-standards.md
 * §8.1). The switch is exhaustive over the sealed {@link PaymentDomainEvent}, so a new event does
 * not compile until this decides what, if anything, it publishes.
 */
public final class PaymentOutboxTranslator {

    private PaymentOutboxTranslator() {}

    public static List<OutboxMessage> translate(PaymentDomainEvent event) {
        return switch (event) {
            case PaymentSucceeded e ->
                    List.of(
                            new PaymentSucceededIntegrationEvent(
                                    e.paymentId().value(),
                                    e.registrationId().value(),
                                    e.occurredAt()));
            case RefundRequired e ->
                    List.of(new PaymentRefundRequested(e.paymentId().value(), e.occurredAt()));
        };
    }
}
