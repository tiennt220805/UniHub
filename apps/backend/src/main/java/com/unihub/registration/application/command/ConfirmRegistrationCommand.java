package com.unihub.registration.application.command;

import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Objects;

/** A payment for this registration succeeded; confirm it if it is still {@code RESERVED}. */
public record ConfirmRegistrationCommand(PaymentId paymentId, RegistrationId registrationId) {

    public ConfirmRegistrationCommand {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(registrationId);
    }
}
