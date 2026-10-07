package com.unihub.user.infrastructure.out.persistence;

import static com.unihub.user.infrastructure.out.persistence.PermissionRowBuilder.aPermission;
import static com.unihub.user.infrastructure.out.persistence.RoleRowBuilder.aRole;
import static com.unihub.user.infrastructure.out.persistence.UserRowBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-01 (INV-01): a user's permissions are resolved purely from data — {@code user_roles} then
 * {@code role_permissions} — so reconfiguring roles changes the answer with no code change
 * (architecture.md §5.1, user.md §1).
 */
class JdbcUserPermissionQueriesIT extends RolledBackJdbcSupport {

    private JdbcUserPermissionQueries queries;

    @BeforeEach
    void createQueries() {
        queries = new JdbcUserPermissionQueries(jdbc);
    }

    private java.util.Set<String> permissionsOf(UUID userId) {
        return queries.permissionCodesOf(UserId.of(userId));
    }

    @Test
    @DisplayName("a user with no role has no permissions")
    void noRole_noPermissions() {
        var user = anActiveUser().insert(jdbc);

        assertThat(permissionsOf(user)).isEmpty();
    }

    @Test
    @DisplayName("a role with no permissions grants nothing")
    void roleWithoutPermissions_grantsNothing() {
        var user = anActiveUser().insert(jdbc);
        RbacLinks.assignRole(jdbc, user, aRole().insert(jdbc));

        assertThat(permissionsOf(user)).isEmpty();
    }

    @Test
    @DisplayName("resolves the permissions of the user's role, and only those")
    void resolvesPermissionsOfAssignedRoleOnly() {
        var user = anActiveUser().insert(jdbc);
        var organizer = aRole().insert(jdbc);
        var staff = aRole().insert(jdbc);
        var create = aPermission().withCode("T_CREATE").insert(jdbc);
        var scan = aPermission().withCode("T_SCAN").insert(jdbc);
        aPermission().withCode("T_UNGRANTED").insert(jdbc);
        RbacLinks.grantPermission(jdbc, organizer, create);
        RbacLinks.grantPermission(jdbc, staff, scan);
        RbacLinks.assignRole(jdbc, user, organizer);

        assertThat(permissionsOf(user)).containsExactly("T_CREATE");
    }

    @Test
    @DisplayName("flattens permissions across several roles and de-duplicates shared ones")
    void flattensAndDeduplicatesAcrossRoles() {
        var user = anActiveUser().insert(jdbc);
        var roleA = aRole().insert(jdbc);
        var roleB = aRole().insert(jdbc);
        var shared = aPermission().withCode("T_SHARED").insert(jdbc);
        var onlyA = aPermission().withCode("T_ONLY_A").insert(jdbc);
        var onlyB = aPermission().withCode("T_ONLY_B").insert(jdbc);
        RbacLinks.grantPermission(jdbc, roleA, shared);
        RbacLinks.grantPermission(jdbc, roleA, onlyA);
        RbacLinks.grantPermission(jdbc, roleB, shared);
        RbacLinks.grantPermission(jdbc, roleB, onlyB);
        RbacLinks.assignRole(jdbc, user, roleA);
        RbacLinks.assignRole(jdbc, user, roleB);

        assertThat(permissionsOf(user))
                .containsExactlyInAnyOrder("T_SHARED", "T_ONLY_A", "T_ONLY_B");
    }

    @Test
    @DisplayName("another user's roles never leak into the result")
    void doesNotLeakAcrossUsers() {
        var alice = anActiveUser().insert(jdbc);
        var bob = anActiveUser().insert(jdbc);
        var role = aRole().insert(jdbc);
        RbacLinks.grantPermission(jdbc, role, aPermission().withCode("T_BOB_ONLY").insert(jdbc));
        RbacLinks.assignRole(jdbc, bob, role);

        assertThat(permissionsOf(alice)).isEmpty();
        assertThat(permissionsOf(bob)).containsExactly("T_BOB_ONLY");
    }

    @Test
    @DisplayName("granting and revoking a permission on a role changes the result with data only")
    void reconfiguringRolePermissions_changesResult_withoutCodeChange() {
        var user = anActiveUser().insert(jdbc);
        var role = aRole().insert(jdbc);
        var first = aPermission().withCode("T_FIRST").insert(jdbc);
        var second = aPermission().withCode("T_SECOND").insert(jdbc);
        RbacLinks.grantPermission(jdbc, role, first);
        RbacLinks.assignRole(jdbc, user, role);
        assertThat(permissionsOf(user)).containsExactly("T_FIRST");

        RbacLinks.grantPermission(jdbc, role, second);
        assertThat(permissionsOf(user)).containsExactlyInAnyOrder("T_FIRST", "T_SECOND");

        jdbc.update(
                "DELETE FROM role_permissions WHERE role_id = ? AND permission_id = ?",
                role,
                first);
        assertThat(permissionsOf(user)).containsExactly("T_SECOND");
    }

    @Test
    @DisplayName("reassigning a user to a different role changes the result with data only")
    void reassigningRole_changesResult_withoutCodeChange() {
        var user = anActiveUser().insert(jdbc);
        var oldRole = aRole().insert(jdbc);
        var newRole = aRole().insert(jdbc);
        RbacLinks.grantPermission(jdbc, oldRole, aPermission().withCode("T_OLD").insert(jdbc));
        RbacLinks.grantPermission(jdbc, newRole, aPermission().withCode("T_NEW").insert(jdbc));
        RbacLinks.assignRole(jdbc, user, oldRole);
        assertThat(permissionsOf(user)).containsExactly("T_OLD");

        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND role_id = ?", user, oldRole);
        RbacLinks.assignRole(jdbc, user, newRole);

        assertThat(permissionsOf(user)).containsExactly("T_NEW");
    }
}
