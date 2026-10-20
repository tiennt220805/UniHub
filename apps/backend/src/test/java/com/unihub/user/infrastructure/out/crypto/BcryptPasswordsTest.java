package com.unihub.user.infrastructure.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BcryptPasswordsTest {

    private final BcryptPasswords passwords = new BcryptPasswords();

    @Test
    @DisplayName("a hash verifies the password it was made from, and no other")
    void hash_roundTrips() {
        var hash = passwords.hash("correct horse");

        assertThat(hash).startsWith("$2").isNotEqualTo("correct horse");
        assertThat(passwords.matches("correct horse", hash)).isTrue();
        assertThat(passwords.matches("wrong", hash)).isFalse();
    }

    @Test
    @DisplayName("two hashes of one password differ (salted)")
    void hash_isSalted() {
        assertThat(passwords.hash("same")).isNotEqualTo(passwords.hash("same"));
    }

    @Test
    @DisplayName("no stored hash never matches, whatever is typed")
    void nullHash_neverMatches() {
        assertThat(passwords.matches("anything", null)).isFalse();
        assertThat(passwords.matches("", null)).isFalse();
    }

    @Test
    @DisplayName(
            "a password over BCrypt's 72-byte limit never matches, even against its own 72-byte prefix")
    void overlongPassword_neverMatches() {
        var prefix = "p".repeat(72);
        var hash = passwords.hash(prefix);

        assertThat(passwords.matches(prefix, hash)).isTrue();
        assertThat(passwords.matches(prefix + "tail", hash)).isFalse();
        assertThat(passwords.matches("x".repeat(100), null)).isFalse();
    }
}
