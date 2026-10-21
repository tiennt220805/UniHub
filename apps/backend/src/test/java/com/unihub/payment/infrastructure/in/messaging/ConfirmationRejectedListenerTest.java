package com.unihub.payment.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.unihub.payment.application.command.RequireRefundCommand;
import com.unihub.payment.application.port.in.RequireRefundUseCase;
import com.unihub.payment.infrastructure.in.messaging.ConfirmationRejectedListener.ConfirmationRejectedMessage;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfirmationRejectedListenerTest {
    private final RequireRefundUseCase useCase = mock(RequireRefundUseCase.class);
    private final ConfirmationRejectedListener listener = new ConfirmationRejectedListener(useCase);

    @Test
    @DisplayName(
            "a confirmation.rejected message becomes one require-refund command for its payment")
    void maps() {
        var payment = UUID.randomUUID();

        listener.onConfirmationRejected(
                new ConfirmationRejectedMessage(payment, UUID.randomUUID(), "HOLD_EXPIRED", 1));

        var command = ArgumentCaptor.forClass(RequireRefundCommand.class);
        verify(useCase).requireRefund(command.capture());
        assertThat(command.getValue().paymentId().value()).isEqualTo(payment);
    }

    @Test
    @DisplayName("an unknown or missing schema version, or no payment id, is non-retryable")
    void invalid_isNonRetryable() {
        for (var version : new Integer[] {null, 2}) {
            assertThatThrownBy(
                            () ->
                                    listener.onConfirmationRejected(
                                            new ConfirmationRejectedMessage(
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID(),
                                                    "x",
                                                    version)))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        assertThatThrownBy(
                        () ->
                                listener.onConfirmationRejected(
                                        new ConfirmationRejectedMessage(
                                                null, UUID.randomUUID(), "x", 1)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }
}
