package com.unihub.shared.infrastructure.security;

import static com.unihub.shared.security.TestJwts.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.unihub.shared.security.TestJwts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * M-02 (INV-01): the verifier accepts exactly one kind of token — HS256, signed with the shared
 * secret, unexpired — and rejects every adversarial variant in security-testing.md §2.2 (S-1…S-6)
 * before any permission is read from it (auth-and-identity.md §3).
 */
class AuthJwtDecoderTest {

    private static final UUID USER = UUID.randomUUID();

    private final JwtDecoder decoder =
            new SecurityConfig().authJwtDecoder(new AuthJwtProperties(SECRET));

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("accepts a well-formed token and exposes sub and the permissions[] claim")
    void accepts_validToken() {
        Jwt jwt = decoder.decode(TestJwts.valid(USER, "WORKSHOP_CREATE", "WORKSHOP_EDIT"));

        assertThat(jwt.getSubject()).isEqualTo(USER.toString());
        assertThat(jwt.getClaimAsStringList("permissions"))
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT");
        assertThat(jwt.getClaims()).doesNotContainKey("role");
    }

    @Test
    @DisplayName("S-1: rejects a token whose payload was edited to add a permission")
    void rejects_tamperedPayload() {
        var token = TestJwts.valid(USER);
        var forged =
                TestJwts.withPayload(
                        token,
                        "{\"sub\":\""
                                + USER
                                + "\",\"permissions\":[\"WORKSHOP_CREATE\"],\"exp\":"
                                + (Instant.now().getEpochSecond() + 1800)
                                + "}");

        assertRejected(forged);
    }

    @Test
    @DisplayName("S-2: rejects a fully privileged token signed with an attacker-chosen secret")
    void rejects_wrongSecret() {
        var now = Instant.now();
        var forged =
                TestJwts.sign(
                        JWSAlgorithm.HS256,
                        "attacker-chosen-secret-attacker-chosen-secret-attacker-chosen-secret",
                        TestJwts.claims(
                                USER,
                                List.of("WORKSHOP_CREATE", "WORKSHOP_EDIT", "REPORT_VIEW"),
                                now,
                                now.plus(Duration.ofMinutes(30))));

        assertRejected(forged);
    }

    @Test
    @DisplayName("S-3: rejects an unsigned token with alg none")
    void rejects_algNone() {
        var now = Instant.now();
        var unsigned =
                TestJwts.unsigned(
                        TestJwts.claims(
                                USER,
                                List.of("WORKSHOP_CREATE"),
                                now,
                                now.plus(Duration.ofMinutes(30))));

        assertRejected(unsigned);
    }

    @Test
    @DisplayName(
            "S-4: rejects a token signed with a different HMAC size, even with the right secret")
    void rejects_otherHmacSize() {
        var now = Instant.now();
        var token =
                TestJwts.sign(
                        JWSAlgorithm.HS512,
                        SECRET,
                        TestJwts.claims(USER, List.of(), now, now.plus(Duration.ofMinutes(30))));

        assertRejected(token);
    }

    @Test
    @DisplayName("S-4: rejects a token whose header claims an asymmetric algorithm")
    void rejects_asymmetricAlgorithmClaim() {
        assertRejected(TestJwts.withHeaderAlg(TestJwts.valid(USER), "RS256"));
    }

    @Test
    @DisplayName("S-5: rejects an expired token")
    void rejects_expiredToken() {
        assertRejected(TestJwts.expired(USER, Duration.ofHours(1)));
    }

    @Test
    @DisplayName("S-5: rejects a token that expired seconds ago — no clock-skew leeway (§4)")
    void rejects_justExpiredToken_noLeeway() {
        assertRejected(TestJwts.expired(USER, Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("rejects a correctly signed token that has no exp claim, so it could never expire")
    void rejects_tokenWithoutExpiry() {
        var token =
                TestJwts.sign(
                        JWSAlgorithm.HS256,
                        SECRET,
                        TestJwts.claims(USER, List.of("WORKSHOP_CREATE"), Instant.now(), null));

        assertRejected(token);
    }

    @Test
    @DisplayName("S-6: rejects truncated, non-JWT and empty input")
    void rejects_malformedInput() {
        var valid = TestJwts.valid(USER);

        assertRejected(valid.substring(0, valid.lastIndexOf('.')));
        assertRejected("not-a-jwt");
        assertRejected("a.b.c");
        assertRejected("");
    }
}
