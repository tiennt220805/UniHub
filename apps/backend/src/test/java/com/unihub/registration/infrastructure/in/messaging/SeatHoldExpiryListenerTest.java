package com.unihub.registration.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.unihub.registration.application.command.ExpireSeatHoldCommand;
import com.unihub.registration.application.port.in.ExpireSeatHoldUseCase;
import com.unihub.registration.infrastructure.in.messaging.SeatHoldExpiryListener.SeatHoldExpiryMessage;
import com.unihub.shared.messaging.NonRetryableMessageException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SeatHoldExpiryListenerTest {
    private final ExpireSeatHoldUseCase useCase = mock(ExpireSeatHoldUseCase.class);
    private final SeatHoldExpiryListener listener = new SeatHoldExpiryListener(useCase);

    @Test
    @DisplayName("a hold.expired message becomes one expire command, ids intact")
    void maps() {
        var registration = UUID.randomUUID();
        var workshop = UUID.randomUUID();

        listener.onHoldExpired(
                new SeatHoldExpiryMessage(registration, workshop, Instant.now(), Instant.now(), 1));

        var command = ArgumentCaptor.forClass(ExpireSeatHoldCommand.class);
        verify(useCase).expire(command.capture());
        assertThat(command.getValue().registrationId().value()).isEqualTo(registration);
        assertThat(command.getValue().workshopId().value()).isEqualTo(workshop);
    }

    @Test
    @DisplayName("a schema version it does not know, or none, goes straight to the parking lot")
    void unsupportedVersion_isNonRetryable() {
        for (var version : new Integer[] {null, 0, 2}) {
            assertThatThrownBy(
                            () ->
                                    listener.onHoldExpired(
                                            new SeatHoldExpiryMessage(
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID(),
                                                    Instant.now(),
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
                                listener.onHoldExpired(
                                        new SeatHoldExpiryMessage(
                                                null, UUID.randomUUID(), null, null, 1)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(
                        () ->
                                listener.onHoldExpired(
                                        new SeatHoldExpiryMessage(
                                                UUID.randomUUID(), null, null, null, 1)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("a failure of the use case is not caught: the shared error handler decides")
    void failures_propagate() {
        var failure = new IllegalStateException("boom");
        org.mockito.Mockito.doThrow(failure)
                .when(useCase)
                .expire(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(
                        () ->
                                listener.onHoldExpired(
                                        new SeatHoldExpiryMessage(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                null,
                                                null,
                                                1)))
                .isSameAs(failure);
    }
}
