package com.unihub.registration.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.registration.application.command.ConfirmRegistrationCommand;
import com.unihub.registration.application.usecase.ConfirmRegistrationTransaction.Effect;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfirmRegistrationTest {
    private static final ConfirmRegistrationCommand COMMAND =
            new ConfirmRegistrationCommand(PaymentId.newId(), RegistrationId.newId());

    private final ConfirmRegistrationTransaction transaction =
            mock(ConfirmRegistrationTransaction.class);
    private final ConfirmRegistration confirm = new ConfirmRegistration(transaction);

    @Test
    @DisplayName(
            "a conditional update lost to the expiry is retried in a new transaction, not an error")
    void lostRace_isRetried() {
        when(transaction.attempt(COMMAND))
                .thenThrow(new OptimisticConflictException("REGISTRATION", UUID.randomUUID()))
                .thenReturn(Effect.REJECTED);

        confirm.confirm(COMMAND);

        verify(transaction, times(2)).attempt(COMMAND);
    }

    @Test
    @DisplayName("when every attempt loses, the failure reaches the shared error handler")
    void exhausted_propagates() {
        when(transaction.attempt(any()))
                .thenThrow(new OptimisticConflictException("REGISTRATION", UUID.randomUUID()));

        assertThatThrownBy(() -> confirm.confirm(COMMAND))
                .isInstanceOf(OptimisticConflictException.class);

        verify(transaction, times(3)).attempt(COMMAND);
    }
}
