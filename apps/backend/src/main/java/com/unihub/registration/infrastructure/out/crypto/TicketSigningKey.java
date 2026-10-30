package com.unihub.registration.infrastructure.out.crypto;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;

/**
 * The private EC P-256 key the QR e-ticket is signed with (checkin.md §4; Phase 5 checklist D47,
 * D49). Read from {@code unihub.ticket.signing-key} ({@code UNIHUB_TICKET_SIGNING_KEY}), a private
 * JWK, and checked once at start, so a misconfigured environment stops the application instead of
 * issuing tickets no device can verify.
 *
 * <p>The key is the one secret of the check-in design that no client ever receives, so this class
 * never prints it: {@link #toString()} is redacted and a rejected value is described, never echoed
 * (not even through the parser's own message, which can quote its input). Only the Registration
 * module may use it; the key stays package-private for the signer that lives beside this class.
 */
public final class TicketSigningKey {

    static final String PROPERTY = "unihub.ticket.signing-key";
    static final String ENVIRONMENT = "UNIHUB_TICKET_SIGNING_KEY";

    private static final String HOW_TO_SET =
            PROPERTY
                    + " ("
                    + ENVIRONMENT
                    + ") must be a private EC P-256 JWK; apps/backend/scripts/generate-ticket-key.sh"
                    + " makes one for local runs";

    private final ECKey key;

    private TicketSigningKey(ECKey key) {
        this.key = key;
    }

    /**
     * Parses and checks the configured value.
     *
     * @throws IllegalStateException with a message that names the property and what is wrong, and
     *     never contains the value, when it is blank, not a JWK, not an EC P-256 key, has no
     *     private member, or has a private member that does not belong to its public coordinates
     */
    public static TicketSigningKey parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw invalid("it is not set");
        }
        JWK parsed;
        try {
            parsed = JWK.parse(configured);
        } catch (java.text.ParseException | RuntimeException e) {
            // The cause is dropped on purpose: a parser message can quote the text it choked on.
            throw invalid("it is not a JWK");
        }
        if (!(parsed instanceof ECKey ec)) {
            throw invalid("it is a " + parsed.getKeyType().getValue() + " key, not an EC key");
        }
        if (!Curve.P_256.equals(ec.getCurve())) {
            throw invalid("it uses curve " + ec.getCurve() + ", not P-256 (ES256)");
        }
        if (!ec.isPrivate()) {
            throw invalid("it has no private member \"d\": a public key cannot sign tickets");
        }
        requirePrivateMatchesPublic(ec);
        return new TicketSigningKey(ec);
    }

    /**
     * A private member that is not the one of the public coordinates signs without complaint and
     * yields tickets that fail against the very key the devices fetch, so sign and verify once.
     */
    private static void requirePrivateMatchesPublic(ECKey ec) {
        try {
            var jws = new JWSObject(new JWSHeader(JWSAlgorithm.ES256), new Payload("self-test"));
            jws.sign(new ECDSASigner(ec));
            if (!jws.verify(new ECDSAVerifier(ec.toPublicJWK()))) {
                throw invalid("its private member \"d\" does not match its public x and y");
            }
        } catch (JOSEException e) {
            throw invalid("it could not sign and verify a test message");
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException(
                PROPERTY + " is invalid: " + reason + ". " + HOW_TO_SET + ".");
    }

    /** For the signer beside this class; never exposed outside the package. */
    ECKey key() {
        return key;
    }

    @Override
    public String toString() {
        return "TicketSigningKey[ES256, redacted]";
    }
}
