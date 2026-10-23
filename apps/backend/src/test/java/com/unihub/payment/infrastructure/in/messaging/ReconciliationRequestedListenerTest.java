package com.unihub.payment.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.payment.infrastructure.in.messaging.ReconciliationRequestedListener.ReconciliationRequestedMessage;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReconciliationRequestedListenerTest {
    private final ReconcilePaymentUseCase useCase = mock(ReconcilePaymentUseCase.class);
    private final ReconciliationRequestedListener listener =
            new ReconciliationRequestedListener(useCase);

    @Test
    @DisplayName("a reconciliation.requested message becomes one reconcile command for its payment")
    void maps() {
        var payment = UUID.randomUUID();

        listener.onReconciliationRequested(new ReconciliationRequestedMessage(payment, 1));

        var command = ArgumentCaptor.forClass(ReconcilePaymentCommand.class);
        verify(useCase).reconcile(command.capture());
        assertThat(command.getValue().paymentId().value()).isEqualTo(payment);
    }

    @Test
    @DisplayName("an unknown or missing schema version, or no payment id, is non-retryable")
    void invalid_isNonRetryable() {
        for (var version : new Integer[] {null, 2}) {
            assertThatThrownBy(
                            () ->
                                    listener.onReconciliationRequested(
                                            new ReconciliationRequestedMessage(
                                                    UUID.randomUUID(), version)))
                    .isInstanceOf(NonRetryableMessageException.class);
        }
        assertThatThrownBy(
                        () ->
                                listener.onReconciliationRequested(
                                        new ReconciliationRequestedMessage(null, 1)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }
}
