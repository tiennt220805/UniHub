package com.unihub.registration.application.port.in;

import com.unihub.registration.application.command.ConfirmRegistrationCommand;

public interface ConfirmRegistrationUseCase {

    /**
     * Idempotent under redelivery: a registration already confirmed is left alone, one that can no
     * longer be confirmed produces a rejection for Payment instead of a transition.
     */
    void confirm(ConfirmRegistrationCommand command);
}
