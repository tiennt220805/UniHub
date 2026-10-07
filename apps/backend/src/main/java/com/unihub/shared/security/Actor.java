package com.unihub.shared.security;

import com.unihub.shared.domain.id.UserId;
import java.util.Objects;
import java.util.Set;

/**
 * Who performs an operation (backend-coding-standards.md §13.1). Maps 1:1 onto {@code
 * audit_events.actor_type / actor_user_id}. The nested names avoid "User"/"System", which would
 * shadow UserId-adjacent names and java.lang.System.
 */
public sealed interface Actor permits Actor.UserActor, Actor.SystemActor {

    /** A person, resolved from the JWT. Every HTTP-originated Command carries this type. */
    record UserActor(UserId userId, Set<String> permissions) implements Actor {

        public UserActor {
            Objects.requireNonNull(userId);
            permissions = Set.copyOf(permissions);
        }

        public boolean has(String permission) {
            return permissions.contains(permission);
        }
    }

    /**
     * An operation no person performs. {@code process} names the job, e.g. "seat-hold-expiry",
     * "payment-reconciliation".
     */
    record SystemActor(String process) implements Actor {

        public SystemActor {
            if (process == null || process.isBlank()) {
                throw new IllegalArgumentException("process is required");
            }
        }
    }

    static SystemActor system(String process) {
        return new SystemActor(process);
    }
}
