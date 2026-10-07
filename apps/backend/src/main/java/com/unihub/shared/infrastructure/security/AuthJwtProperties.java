package com.unihub.shared.infrastructure.security;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared HMAC secret every Backend API pod verifies auth JWTs with (auth-and-identity.md §3).
 * Bound from the environment ({@code UNIHUB_SECURITY_JWT_SECRET}); it has no default anywhere, so a
 * pod without it refuses to start instead of running with a guessable key.
 */
@ConfigurationProperties(prefix = "unihub.security.jwt")
public record AuthJwtProperties(String secret) {

    /** HS256 needs a key of at least 256 bits (RFC 7518 §3.2). */
    private static final int MIN_SECRET_BYTES = 32;

    public AuthJwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // Never echo the value: it is a credential.
            throw new IllegalArgumentException(
                    "unihub.security.jwt.secret must be set to at least "
                            + MIN_SECRET_BYTES
                            + " bytes");
        }
    }

    /** Keeps the secret out of logs and exception messages. */
    @Override
    public String toString() {
        return "AuthJwtProperties[secret=****]";
    }
}
