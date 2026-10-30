package com.unihub.registration.infrastructure.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The ticket signing key is required and checked at start (Phase 5 checklist D49, AC49.1): every
 * bad value fails with a message that says what to set, and no message ever carries the value.
 */
class TicketSigningKeyTest {

    @Test
    @DisplayName("a private EC P-256 JWK is accepted, and printing it shows nothing of the key")
    void validKey_isAcceptedAndRedacted() {
        var jwk = TicketTestKeys.newPrivateJwk();

        var key = TicketSigningKey.parse(jwk);

        assertThat(key.key().isPrivate()).isTrue();
        assertThat(key.toString()).doesNotContain(TicketTestKeys.privateMemberOf(jwk));
        assertThat(key.toString()).doesNotContain("\"d\"");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    @DisplayName("AC49.1: a missing or blank key names the property and the variable")
    void blankKey_isRejectedWithAClearMessage(String blank) {
        assertThatThrownBy(() -> TicketSigningKey.parse(blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unihub.ticket.signing-key")
                .hasMessageContaining("UNIHUB_TICKET_SIGNING_KEY")
                .hasMessageContaining("it is not set");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedKeys")
    @DisplayName("AC49.1: a key that cannot sign tickets is rejected, and the message hides it")
    void badKey_isRejectedWithoutEchoingIt(String name, String value, String reason) {
        assertThatThrownBy(() -> TicketSigningKey.parse(value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unihub.ticket.signing-key")
                .hasMessageContaining("UNIHUB_TICKET_SIGNING_KEY")
                .hasMessageContaining(reason)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(value));
    }

    static Stream<Arguments> rejectedKeys() {
        var p384 = TicketTestKeys.newPrivateJwk(Curve.P_384);
        var mismatched = TicketTestKeys.mismatchedPrivateJwk();
        return Stream.of(
                Arguments.of("plain text", "not-a-key-at-all-just-text", "not a JWK"),
                Arguments.of("JSON that is not a JWK", "{\"hello\":\"world\"}", "not a JWK"),
                Arguments.of("truncated JSON", "{\"kty\":\"EC\",\"crv\":", "not a JWK"),
                Arguments.of("RSA", TicketTestKeys.newPrivateRsaJwk(), "not an EC key"),
                Arguments.of("P-384 private key", p384, "not P-256"),
                Arguments.of(
                        "P-256 public key only", TicketTestKeys.newPublicOnlyJwk(), "no private"),
                Arguments.of("private member of another key", mismatched, "does not match"));
    }

    @Test
    @DisplayName("a rejected private key never leaks its private member through the message")
    void rejectedPrivateKey_doesNotLeakD() {
        var p384 = TicketTestKeys.newPrivateJwk(Curve.P_384);
        var d = TicketTestKeys.privateMemberOf(p384);

        assertThatThrownBy(() -> TicketSigningKey.parse(p384))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(d));
    }
}
