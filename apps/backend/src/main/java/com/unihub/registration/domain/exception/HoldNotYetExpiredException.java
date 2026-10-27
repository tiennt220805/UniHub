package com.unihub.registration.domain.exception;

import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;

/**
 * The hold-expiry message arrived while the hold still had time left (clock skew between the broker
 * and the pods, checklist D27). Transient and never acted on: the retry chain's 5 s stage brings
 * the message back. Not an HTTP error, so it is not a {@code DomainException}.
 */
public class HoldNotYetExpiredException extends RuntimeException {

    public HoldNotYetExpiredException(RegistrationId registrationId, Instant holdExpiresAt) {
        super(
                "Hold of registration "
                        + registrationId.value()
                        + " lasts until "
                        + holdExpiresAt
                        + "; not expired yet");
    }
}
