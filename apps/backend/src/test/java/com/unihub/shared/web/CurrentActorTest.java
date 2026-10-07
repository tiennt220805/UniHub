package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.domain.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The JWT claim shape of auth-and-identity.md §2: only sub and permissions[] matter. */
class CurrentActorTest {

    private static Jwt jwt(String subject, Map<String, Object> extraClaims) {
        var builder =
                Jwt.withTokenValue("token")
                        .header("alg", "HS256")
                        .subject(subject)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(1800));
        extraClaims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    @DisplayName("builds the UserActor from sub and the permissions[] claim")
    void from_buildsUserActor() {
        var id = UUID.randomUUID();

        var actor =
                CurrentActor.from(
                        jwt(
                                id.toString(),
                                Map.of(
                                        "permissions",
                                        List.of("WORKSHOP_CREATE", "WORKSHOP_EDIT"))));

        assertThat(actor.userId()).isEqualTo(UserId.of(id));
        assertThat(actor.permissions())
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT");
        assertThat(actor.has("WORKSHOP_CREATE")).isTrue();
        assertThat(actor.has("REPORT_VIEW")).isFalse();
    }

    @Test
    @DisplayName("collapses duplicate permission codes (the claim is a set)")
    void from_deduplicatesPermissions() {
        var actor =
                CurrentActor.from(
                        jwt(
                                UUID.randomUUID().toString(),
                                Map.of("permissions", List.of("REPORT_VIEW", "REPORT_VIEW"))));

        assertThat(actor.permissions()).containsExactly("REPORT_VIEW");
    }

    @Test
    @DisplayName("a token without permissions[] grants nothing (fail closed, not an error)")
    void from_missingPermissionsClaim_hasNoPermissions() {
        var actor = CurrentActor.from(jwt(UUID.randomUUID().toString(), Map.of()));

        assertThat(actor.permissions()).isEmpty();
    }

    @Test
    @DisplayName("a role claim is ignored: it never turns into a permission")
    void from_ignoresRoleClaim() {
        var actor =
                CurrentActor.from(
                        jwt(
                                UUID.randomUUID().toString(),
                                Map.of("role", "ORGANIZER", "permissions", List.of())));

        assertThat(actor.permissions()).isEmpty();
    }

    @Test
    @DisplayName("rejects a subject that is not a user id")
    void from_rejectsNonUuidSubject() {
        assertThatThrownBy(() -> CurrentActor.from(jwt("not-a-uuid", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
