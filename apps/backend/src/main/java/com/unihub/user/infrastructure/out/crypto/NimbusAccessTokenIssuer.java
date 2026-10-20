package com.unihub.user.infrastructure.out.crypto;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.security.AuthJwtProperties;
import com.unihub.user.application.port.out.AccessTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Signs the auth JWT with the same HMAC secret {@code SecurityConfig}'s decoder verifies with
 * (auth-and-identity.md §3): HS256, 30-minute life, no refresh. The claim set is exactly {@code
 * sub}, {@code permissions}, {@code iat}, {@code exp} (§2) — nothing else may be added without
 * revisiting that section.
 */
@Component
class NimbusAccessTokenIssuer implements AccessTokenIssuer {

    static final Duration LIFETIME = Duration.ofMinutes(30);

    private final JwtEncoder encoder;

    NimbusAccessTokenIssuer(AuthJwtProperties properties) {
        var key =
                new SecretKeySpec(
                        properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Override
    public IssuedAccessToken issue(UserId subject, Set<String> permissions, Instant issuedAt) {
        var claims =
                JwtClaimsSet.builder()
                        .subject(subject.value().toString())
                        .claim("permissions", permissions.stream().sorted().toList())
                        .issuedAt(issuedAt)
                        .expiresAt(issuedAt.plus(LIFETIME))
                        .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, LIFETIME);
    }
}
