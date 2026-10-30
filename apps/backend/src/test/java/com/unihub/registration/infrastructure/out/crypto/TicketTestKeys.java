package com.unihub.registration.infrastructure.out.crypto;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * Test-only ticket signing keys, generated at run time: no key material is ever committed (Gitleaks
 * blocks it, and should). {@code IntegrationTestSupport} gives every context one of these, the way
 * it gives them a Redis and an SMTP host.
 */
public final class TicketTestKeys {

    private TicketTestKeys() {}

    /** A fresh private EC P-256 JWK, as the JSON the configuration property holds. */
    public static String newPrivateJwk() {
        return generate(Curve.P_256).toJSONString();
    }

    /** The public half of a fresh P-256 key: valid JWK, but it cannot sign. */
    public static String newPublicOnlyJwk() {
        return generate(Curve.P_256).toPublicJWK().toJSONString();
    }

    /** A private key on another curve (P-384, P-521...). */
    public static String newPrivateJwk(Curve curve) {
        return generate(curve).toJSONString();
    }

    public static String newPrivateRsaJwk() {
        try {
            return new RSAKeyGenerator(2048).generate().toJSONString();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The private member {@code d} of a private JWK, so a test can assert it is never printed. */
    public static String privateMemberOf(String privateJwk) {
        try {
            return com.nimbusds.jose.jwk.ECKey.parse(privateJwk).getD().toString();
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code x} and {@code y} of one key with the {@code d} of another: a mismatched pair. */
    public static String mismatchedPrivateJwk() {
        try {
            var one = generate(Curve.P_256);
            var other = generate(Curve.P_256);
            return new com.nimbusds.jose.jwk.ECKey.Builder(Curve.P_256, one.getX(), one.getY())
                    .d(other.getD())
                    .build()
                    .toJSONString();
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.nimbusds.jose.jwk.ECKey generate(Curve curve) {
        try {
            return new ECKeyGenerator(curve).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
