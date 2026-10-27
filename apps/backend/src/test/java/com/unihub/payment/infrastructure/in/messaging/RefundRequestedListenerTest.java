package com.unihub.payment.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.unihub.payment.application.command.RefundPaymentCommand;
import com.unihub.payment.application.port.in.RefundPaymentUseCase;
import com.unihub.payment.infrastructure.in.messaging.RefundRequestedListener.RefundRequestedMessage;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefundRequestedListenerTest {
    private final RefundPaymentUseCase useCase = mock(RefundPaymentUseCase.class);
    private final RefundRequestedListener listener = new RefundRequestedListener(useCase);

    @Test
    @DisplayName("a refund.requested message becomes one refund command, payment id intact")
    void maps() {
        var payment = UUID.randomUUID();

        listener.onRefundRequested(new RefundRequestedMessage(payment, 1));

        var command = ArgumentCaptor.forClass(RefundPaymentCommand.class);
        verify(useCase).refund(command.capture());
        assertThat(command.getValue().paymentId().value()).isEqualTo(payment);
    }

    @Test
    @DisplayName("a schema version it does not know, or none, goes straight to the parking lot")
    void unsupportedVersion_isNonRetryable() {
        for (var version : new Integer[] {null, 0, 2}) {
            assertThatThrownBy(
                            () ->
                                    listener.onRefundRequested(
                                            new RefundRequestedMessage(UUID.randomUUID(), version)))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("a message without a payment id can never succeed: non-retryable")
    void missingId_isNonRetryable() {
        assertThatThrownBy(() -> listener.onRefundRequested(new RefundRequestedMessage(null, 1)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("a failure of the use case is not caught: the shared error handler decides")
    void failures_propagate() {
        var failure = new IllegalStateException("boom");
        doThrow(failure).when(useCase).refund(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(
                        () ->
                                listener.onRefundRequested(
                                        new RefundRequestedMessage(UUID.randomUUID(), 1)))
                .isSameAs(failure);
    }
}
