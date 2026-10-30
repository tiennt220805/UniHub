package com.unihub.registration.application.usecase;

import com.unihub.registration.api.PublicTicketKey;
import com.unihub.registration.api.TicketVerificationKey;
import com.unihub.registration.application.port.out.TicketSigner;
import org.springframework.stereotype.Service;

/** {@link TicketVerificationKey}: the signer's public key, handed to Check-in as plain values. */
@Service
class RegistrationTicketKey implements TicketVerificationKey {
    private final TicketSigner signer;

    RegistrationTicketKey(TicketSigner signer) {
        this.signer = signer;
    }

    @Override
    public PublicTicketKey current() {
        return signer.publicKey();
    }
}
