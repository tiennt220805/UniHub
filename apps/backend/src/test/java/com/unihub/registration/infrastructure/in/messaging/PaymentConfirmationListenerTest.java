package com.unihub.registration.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.port.in.ConfirmRegistrationUseCase;
import com.unihub.registration.infrastructure.in.messaging.PaymentConfirmationListener.PaymentSucceededMessage;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentConfirmationListenerTest {
    private final ConfirmRegistrationUseCase useCase = mock(ConfirmRegistrationUseCase.class);
    private final PaymentConfirmationListener listener = new PaymentConfirmationListener(useCase);

    @Test
    @DisplayName("a payment.charge.succeeded message becomes one confirm command, ids intact")
    void maps() {
        var payment = UUID.randomUUID();
        var registration = UUID.randomUUID();

        listener.onPaymentSucceeded(
                new PaymentSucceededMessage(payment, registration, Instant.now(), 1));

        var command = ArgumentCaptor.forClass(ConfirmRegistrationCommand.class);
        verify(useCase).confirm(command.capture());
        assertThat(command.getValue().paymentId().value()).isEqualTo(payment);
        assertThat(command.getValue().registrationId().value()).isEqualTo(registration);
    }

    @Test
    @DisplayName("a schema version it does not know, or none, goes straight to the parking lot")
    void unsupportedVersion_isNonRetryable() {
        for (var version : new Integer[] {null, 0, 2}) {
            assertThatThrownBy(
                            () ->
                                    listener.onPaymentSucceeded(
                                            new PaymentSucceededMessage(
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID(),
                                                    Instant.now(),
                                                    version)))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("a message without its ids can never succeed: non-retryable")
    void missingIds_isNonRetryable() {
        assertThatThrownBy(
                        () ->
                                listener.onPaymentSucceeded(
                                        new PaymentSucceededMessage(
                                                null, UUID.randomUUID(), null, 1)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onPaymentSucceeded(
                                        new PaymentSucceededMessage(
                                                UUID.randomUUID(), null, null, 1)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("the listener never swallows a failure of the use case")
    void failure_propagates() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(useCase)
                .confirm(any());

        assertThatThrownBy(
                        () ->
                                listener.onPaymentSucceeded(
                                        new PaymentSucceededMessage(
                                                UUID.randomUUID(), UUID.randomUUID(), null, 1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
