package com.unihub.shared.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only JWT forge (security-testing.md §3): builds valid tokens and the adversarial variants
 * S-1 to S-6 with nimbus-jose-jwt, the server's own JOSE library. Production tokens come from the
 * login endpoint; this forge exists for the tokens login never issues (expired, tampered, {@code
 * alg} confused, extra claims).
 */
public final class TestJwts {

    /** The HMAC secret the test application context is configured with (64+ ASCII bytes). */
    public static final String SECRET =
            "unihub-test-only-hmac-secret-0123456789-0123456789-0123456789-abcd";

    private TestJwts() {}

    public static JWTClaimsSet claims(
            UUID subject, List<String> permissions, Instant issuedAt, Instant expiresAt) {
        var builder =
                new JWTClaimsSet.Builder()
                        .subject(subject.toString())
                        .claim("permissions", permissions)
                        .issueTime(Date.from(issuedAt));
        if (expiresAt != null) {
            builder.expirationTime(Date.from(expiresAt));
        }
        return builder.build();
    }

    /** A well-formed token: HS256, signed with {@link #SECRET}, 30-minute life. */
    public static String valid(UUID subject, String... permissions) {
        var now = Instant.now();
        return sign(
                JWSAlgorithm.HS256,
                SECRET,
                claims(subject, List.of(permissions), now, now.plus(Duration.ofMinutes(30))));
    }

    /**
     * Correctly signed and unexpired, with {@code permissions[]} empty plus arbitrary extra claims
     * — e.g. a {@code role} or {@code scope} claim that must never be honored (S-7).
     */
    public static String withClaims(UUID subject, Map<String, Object> extraClaims) {
        var now = Instant.now();
        var builder =
                new JWTClaimsSet.Builder()
                        .subject(subject.toString())
                        .claim("permissions", List.of())
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(Duration.ofMinutes(30))));
        extraClaims.forEach(builder::claim);
        return sign(JWSAlgorithm.HS256, SECRET, builder.build());
    }

    /** Correctly signed, but {@code exp} lies {@code ago} in the past. */
    public static String expired(UUID subject, Duration ago, String... permissions) {
        var now = Instant.now();
        return sign(
                JWSAlgorithm.HS256,
                SECRET,
                claims(
                        subject,
                        List.of(permissions),
                        now.minus(Duration.ofHours(1)),
                        now.minus(ago)));
    }

    public static String sign(JWSAlgorithm algorithm, String secret, JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** S-1: keep the original header and signature, swap in a different payload. */
    public static String withPayload(String token, String payloadJson) {
        var parts = token.split("\\.");
        var payload = Base64URL.encode(payloadJson.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + payload + "." + parts[2];
    }

    /** S-3: an unsigned token whose header says {@code "alg":"none"}. */
    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /** S-4: keep payload and signature, but make the header claim a different algorithm. */
    public static String withHeaderAlg(String token, String algorithm) {
        var parts = token.split("\\.");
        var header = "{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\"}";
        return Base64URL.encode(header.getBytes(StandardCharsets.UTF_8))
                + "."
                + parts[1]
                + "."
                + parts[2];
    }
}
