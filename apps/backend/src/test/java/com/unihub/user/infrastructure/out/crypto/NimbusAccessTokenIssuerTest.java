package com.unihub.user.infrastructure.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.security.AuthJwtProperties;
import com.unihub.shared.security.TestJwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class NimbusAccessTokenIssuerTest {

    private static final Instant ISSUED = Instant.parse("2026-10-20T03:00:00Z");

    private final NimbusAccessTokenIssuer issuer =
            new NimbusAccessTokenIssuer(new AuthJwtProperties(TestJwts.SECRET));

    private NimbusJwtDecoder decoder(String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // ISSUED is a fixed instant: only the signature and claim shape are under test here.
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder;
    }

    @Test
    @DisplayName(
            "the token verifies under the shared secret and carries exactly sub, permissions, iat, exp")
    void issue_claimShape() {
        var user = UserId.newId();

        var token = issuer.issue(user, Set.of("WORKSHOP_EDIT", "WORKSHOP_CREATE"), ISSUED);
        var jwt = decoder(TestJwts.SECRET).decode(token.value());

        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getClaims().keySet())
                .containsExactlyInAnyOrder("sub", "permissions", "iat", "exp");
        assertThat(jwt.getSubject()).isEqualTo(user.value().toString());
        assertThat(jwt.getClaimAsStringList("permissions"))
                .containsExactly("WORKSHOP_CREATE", "WORKSHOP_EDIT");
        assertThat(jwt.getIssuedAt()).isEqualTo(ISSUED);
        assertThat(jwt.getExpiresAt()).isEqualTo(ISSUED.plus(Duration.ofMinutes(30)));
        assertThat(token.lifetime()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("an empty permission set is an empty array, not a missing claim")
    void issue_noPermissions() {
        var jwt =
                decoder(TestJwts.SECRET)
                        .decode(issuer.issue(UserId.newId(), Set.of(), ISSUED).value());

        assertThat(jwt.getClaimAsStringList("permissions")).isEmpty();
    }

    @Test
    @DisplayName("a different secret does not verify it")
    void issue_wrongSecretRejects() {
        var token = issuer.issue(UserId.newId(), Set.of(), ISSUED).value();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                decoder("another-secret-another-secret-another-secret-0123")
                                        .decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
}
