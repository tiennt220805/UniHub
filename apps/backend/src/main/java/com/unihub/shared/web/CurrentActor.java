package com.unihub.shared.web;

import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.security.Actor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a verified auth JWT into the {@link Actor.UserActor} a Command carries. Reads exactly the
 * claims auth-and-identity.md §2 defines — {@code sub} and {@code permissions[]}; a {@code role}
 * claim, if one ever appeared, is never consulted (architecture.md §5.1).
 */
public final class CurrentActor {

    private static final String PERMISSIONS_CLAIM = "permissions";

    private CurrentActor() {}

    public static Actor.UserActor from(Jwt jwt) {
        var subject = jwt.getSubject();
        if (subject == null) {
            throw new IllegalArgumentException("token has no subject");
        }
        // UUID.fromString throws IllegalArgumentException for anything that is not a UUID.
        var userId = UserId.of(UUID.fromString(subject));
        List<String> claim = jwt.getClaimAsStringList(PERMISSIONS_CLAIM);
        // No permissions claim means no permissions: fail closed, never fall back to anything else.
        return new Actor.UserActor(userId, claim == null ? Set.of() : new HashSet<>(claim));
    }
}
