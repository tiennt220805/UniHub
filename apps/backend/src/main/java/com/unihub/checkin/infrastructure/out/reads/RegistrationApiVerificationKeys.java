package com.unihub.checkin.infrastructure.out.reads;

import com.unihub.checkin.application.port.out.VerificationKeys;
import com.unihub.checkin.application.result.VerificationKeyResult;
import com.unihub.registration.api.TicketVerificationKey;
import org.springframework.stereotype.Component;

/**
 * {@link VerificationKeys} over {@code registration.api.TicketVerificationKey} (Phase 5 checklist
 * D47): translation only. Registration signs and so holds the private key; what crosses this
 * boundary is already the four public members.
 */
@Component
class RegistrationApiVerificationKeys implements VerificationKeys {
    private final TicketVerificationKey key;

    RegistrationApiVerificationKeys(TicketVerificationKey key) {
        this.key = key;
    }

    @Override
    public VerificationKeyResult current() {
        var current = key.current();
        return new VerificationKeyResult(current.kty(), current.crv(), current.x(), current.y());
    }
}
