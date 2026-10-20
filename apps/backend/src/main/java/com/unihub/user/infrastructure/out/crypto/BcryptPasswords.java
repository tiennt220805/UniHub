package com.unihub.user.infrastructure.out.crypto;

import com.unihub.user.application.port.out.Passwords;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt (cost 10, Spring Security's default) for {@code users.password_hash}. */
@Component
class BcryptPasswords implements Passwords {

    private static final int MAX_BCRYPT_BYTES = 72;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Checked against when there is no real hash, so "no such account" costs one BCrypt too. */
    private final String decoyHash = encoder.encode(UUID.randomUUID().toString());

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        // BCrypt only ever reads the first 72 bytes: depending on the library version a longer
        // input
        // is silently truncated or rejected with an exception. Neither may reach a public endpoint
        // (a 500, or "any password sharing a 72-byte prefix works"), so it simply never matches.
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            return false;
        }
        if (storedHash == null) {
            encoder.matches(rawPassword, decoyHash);
            return false;
        }
        return encoder.matches(rawPassword, storedHash);
    }
}
