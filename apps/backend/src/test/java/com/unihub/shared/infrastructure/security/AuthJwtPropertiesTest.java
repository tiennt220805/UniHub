package com.unihub.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-13 (INV-18): the JWT secret exists only when injected at runtime. There is no default, so a pod
 * without it refuses to start, and the value never appears in logs or error messages.
 */
class AuthJwtPropertiesTest {

    private static final String VALID = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("no secret injected: startup fails instead of running with a default")
    void missingSecret_refused() {
        assertThatThrownBy(() -> new AuthJwtProperties(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unihub.security.jwt.secret must be set");
    }

    @Test
    @DisplayName("a secret shorter than 32 bytes is refused, and the message does not echo it")
    void shortSecret_refused_withoutEchoingIt() {
        var shortSecret = "too-short-secret-value";

        assertThatThrownBy(() -> new AuthJwtProperties(shortSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortSecret);
    }

    @Test
    @DisplayName("a 32-byte secret is accepted")
    void thirtyTwoByteSecret_accepted() {
        assertThat(new AuthJwtProperties(VALID).secret()).isEqualTo(VALID);
    }

    @Test
    @DisplayName("toString never prints the secret")
    void toString_redacts() {
        assertThat(new AuthJwtProperties(VALID).toString())
                .doesNotContain(VALID)
                .isEqualTo("AuthJwtProperties[secret=****]");
    }
}
