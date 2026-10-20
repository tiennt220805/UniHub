package com.unihub.user.application.port.out;

import com.unihub.shared.domain.id.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public interface AccessTokenIssuer {

    /**
     * Signs a token carrying exactly {@code sub}, {@code permissions}, {@code iat} and {@code exp}
     * (auth-and-identity.md §2) — no role, no display fields.
     */
    IssuedAccessToken issue(UserId subject, Set<String> permissions, Instant issuedAt);

    record IssuedAccessToken(String value, Duration lifetime) {

        /** Keeps the bearer token out of logs. */
        @Override
        public String toString() {
            return "IssuedAccessToken[value=****, lifetime=" + lifetime + "]";
        }
    }
}
