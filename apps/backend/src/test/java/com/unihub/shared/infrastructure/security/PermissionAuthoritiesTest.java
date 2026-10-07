package com.unihub.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The bridge between the token's claim shape (auth-and-identity.md §2) and Spring's authorities:
 * exactly {@code permissions[]}, no prefix, nothing else (backend-coding-standards.md §11 — "an
 * empty prefix, so hasAuthority('WORKSHOP_CREATE') matches the claim value exactly").
 */
class PermissionAuthoritiesTest {

    private final SecurityConfig config = new SecurityConfig();

    private static Jwt jwt(Map<String, Object> claims) {
        var builder =
                Jwt.withTokenValue("token")
                        .header("alg", "HS256")
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(1800));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private List<String> authoritiesOf(Map<String, Object> claims) {
        return config
                .permissionsAuthenticationConverter()
                .convert(jwt(claims))
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    @Test
    @DisplayName("each permissions[] entry becomes one authority with exactly that string")
    void permissions_becomeUnprefixedAuthorities() {
        assertThat(authoritiesOf(Map.of("permissions", List.of("WORKSHOP_CREATE", "REPORT_VIEW"))))
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "REPORT_VIEW");
    }

    @Test
    @DisplayName("no permissions claim means no authorities")
    void missingClaim_meansNoAuthorities() {
        assertThat(authoritiesOf(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("a role claim never becomes an authority")
    void roleClaim_isIgnored() {
        assertThat(authoritiesOf(Map.of("role", "ORGANIZER", "permissions", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("scope and scp claims never become authorities (Spring's default would read them)")
    void scopeClaims_areIgnored() {
        assertThat(authoritiesOf(Map.of("scope", "WORKSHOP_CREATE", "scp", List.of("REPORT_VIEW"))))
                .isEmpty();
    }
}
