package com.unihub.registration.infrastructure.out.crypto;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.unihub.registration.api.PublicTicketKey;
import com.unihub.registration.application.port.out.TicketSigner;
import com.unihub.registration.domain.model.Ticket;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;
import java.util.Date;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Signs the QR e-ticket with ES256 (ECDSA over P-256) through nimbus-jose-jwt (checkin.md §4, §5;
 * Phase 5 checklist D47, D48). The protected header is exactly {@code {"alg":"ES256"}}: no {@code
 * typ} and no {@code kid}, because every byte counts in a QR code and the device keeps one key
 * (checkin.md §6). The payload is exactly {@code ticket_code}, {@code workshop_id} and {@code iat}:
 * no {@code exp} (a ticket is authentic for ever; the only revocation is rotating the key) and no
 * personal data (the QR is shown on a screen anyone can photograph).
 */
@Component
class NimbusEs256TicketSigner implements TicketSigner {

    private final TicketSigningKey key;
    private final ECDSASigner signer;
    private final TicketCodeGenerator codes;

    @Autowired
    NimbusEs256TicketSigner(TicketSigningKey key) {
        this(key, new TicketCodeGenerator());
    }

    NimbusEs256TicketSigner(TicketSigningKey key, TicketCodeGenerator codes) {
        this.key = key;
        this.codes = codes;
        try {
            this.signer = new ECDSASigner(key.key());
        } catch (JOSEException e) {
            // TicketSigningKey already signed and verified once at start; this is not reachable.
            throw new IllegalStateException("the ticket signing key cannot sign", e);
        }
    }

    @Override
    public Ticket issue(WorkshopId workshopId, Instant issuedAt) {
        var code = codes.next();
        var claims =
                new JWTClaimsSet.Builder()
                        .claim("ticket_code", code)
                        .claim("workshop_id", workshopId.value().toString())
                        .issueTime(Date.from(issuedAt))
                        .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            // Rolls the confirming transaction back; a free registration is retried with the same
            // Idempotency-Key, a paid one through the payment-confirmation retry chain.
            throw new IllegalStateException("signing a ticket failed", e);
        }
        return new Ticket(code, jwt.serialize());
    }

    /** Built from {@code x} and {@code y} alone: nothing else a key may carry reaches a device. */
    @Override
    public PublicTicketKey publicKey() {
        var ec = key.key();
        return new PublicTicketKey(
                ec.getKeyType().getValue(),
                ec.getCurve().getName(),
                ec.getX().toString(),
                ec.getY().toString());
    }
}
