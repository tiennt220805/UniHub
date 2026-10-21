package com.unihub.payment.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.payment.api.event.PaymentSucceededIntegrationEvent;
import com.unihub.payment.domain.event.PaymentDomainEvent;
import com.unihub.payment.domain.event.PaymentSucceeded;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentOutboxTranslatorTest {
    private static final PaymentId PAYMENT = PaymentId.newId();
    private static final RegistrationId REGISTRATION = RegistrationId.newId();
    private static final Instant AT = Instant.parse("2026-10-21T08:00:00Z");

    @Test
    @DisplayName("PaymentSucceeded becomes one charge.succeeded message with flat UUIDs")
    void succeeded() {
        var messages =
                PaymentOutboxTranslator.translate(new PaymentSucceeded(PAYMENT, REGISTRATION, AT));

        assertThat(messages).hasSize(1);
        var message = messages.getFirst();
        assertThat(message)
                .isEqualTo(
                        new PaymentSucceededIntegrationEvent(
                                PAYMENT.value(), REGISTRATION.value(), AT));
        assertThat(message.routingKey()).isEqualTo("unihub.payment.charge.succeeded");
        assertThat(message.aggregateType()).isEqualTo("PAYMENT");
        assertThat(message.aggregateId()).isEqualTo(PAYMENT.value());
        assertThat(message.schemaVersion()).isEqualTo(1);
        assertThat(message.occurredAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("every permitted domain event has a translation (the switch is exhaustive)")
    void everyPermittedEvent_isCovered() {
        for (var permitted : PaymentDomainEvent.class.getPermittedSubclasses()) {
            assertThat(permitted)
                    .as("a new Payment event needs a case in the translator and a test here")
                    .isIn(PaymentSucceeded.class);
        }
    }
}
