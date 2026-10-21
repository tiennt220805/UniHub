package com.unihub.registration.application.query;

import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import java.util.Objects;

/** One registration, read by the caller; allowed only to its owner. */
public record GetRegistrationQuery(UserId caller, RegistrationId registrationId) {

    public GetRegistrationQuery {
        Objects.requireNonNull(caller);
        Objects.requireNonNull(registrationId);
    }
}
