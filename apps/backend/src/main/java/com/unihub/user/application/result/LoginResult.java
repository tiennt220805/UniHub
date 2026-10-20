package com.unihub.user.application.result;

import com.unihub.shared.domain.id.UserId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome of a login attempt. A refusal is one variant with no reason: a wrong password, an unknown
 * email, a {@code SUSPENDED} account and an account with no password yet are indistinguishable to
 * the caller (auth-and-identity.md §7, paths/user.yaml {@code login}).
 */
public sealed interface LoginResult {

    record Authenticated(
            UserId userId,
            String fullName,
            String email,
            List<String> roles,
            Set<String> permissions,
            String accessToken,
            int expiresInSeconds)
            implements LoginResult {

        public Authenticated {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(fullName);
            Objects.requireNonNull(email);
            Objects.requireNonNull(accessToken);
            roles = List.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }

        /** Keeps the bearer token out of logs. */
        @Override
        public String toString() {
            return "Authenticated[userId=" + userId + ", accessToken=****]";
        }
    }

    record Rejected() implements LoginResult {}
}
