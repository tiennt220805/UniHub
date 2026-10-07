package com.unihub.user.infrastructure.out.persistence;

import static com.unihub.user.infrastructure.out.persistence.PermissionRowBuilder.aPermission;
import static com.unihub.user.infrastructure.out.persistence.RoleRowBuilder.aRole;
import static com.unihub.user.infrastructure.out.persistence.UserRowBuilder.aLegacyStudent;
import static com.unihub.user.infrastructure.out.persistence.UserRowBuilder.aSuspendedUser;
import static com.unihub.user.infrastructure.out.persistence.UserRowBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the Flyway-migrated User-module tables ({@code users}, {@code roles}, {@code permissions},
 * {@code user_roles}, {@code role_permissions}) protect their own invariants, so RBAC (Mục 2.2) can
 * rely on them (architecture.md §5.1: users ↔ roles ↔ permissions, permission-based).
 */
class UserSchemaIT extends RolledBackJdbcSupport {

    // ---- users ----

    @Test
    @DisplayName("an active user round-trips, with status ACTIVE and DB-filled timestamps")
    void user_roundTrip() {
        var id = anActiveUser().withFullName("Nguyen Van A").insert(jdbc);

        var row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);

        assertThat(row.get("full_name")).isEqualTo("Nguyen Van A");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("legacy_student_id")).isNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    @DisplayName("status defaults to ACTIVE when omitted")
    void user_statusDefaultsToActive() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, ?)",
                id,
                "d-" + id + "@example.test",
                "Default Status");

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a suspended user is storable")
    void user_suspendedIsAllowed() {
        var id = aSuspendedUser().insert(jdbc);

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id))
                .isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("a legacy-synced student may have no password; legacy ids and nulls coexist")
    void user_legacyStudentWithoutPassword() {
        aLegacyStudent().insert(jdbc);
        aLegacyStudent().insert(jdbc);
        anActiveUser().insert(jdbc);
        anActiveUser().insert(jdbc);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE password_hash IS NULL",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("rejects a second user with the same email")
    void user_emailIsUnique() {
        anActiveUser().withEmail("dup@example.test").insert(jdbc);

        assertRejected(
                () -> anActiveUser().withEmail("dup@example.test").insert(jdbc), UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("rejects a second user with the same legacy student id")
    void user_legacyStudentIdIsUnique() {
        aLegacyStudent().withLegacyStudentId("SV000001").insert(jdbc);

        assertRejected(
                () -> aLegacyStudent().withLegacyStudentId("SV000001").insert(jdbc),
                UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("rejects a status outside ACTIVE/SUSPENDED")
    void user_statusMustBeKnown() {
        assertRejected(() -> anActiveUser().withStatus("DELETED").insert(jdbc), CHECK_VIOLATION);
    }

    @Test
    @DisplayName("rejects a user without an email")
    void user_emailIsRequired() {
        assertRejected(() -> anActiveUser().withEmail(null).insert(jdbc), NOT_NULL_VIOLATION);
    }

    @Test
    @DisplayName("rejects a user without a full name")
    void user_fullNameIsRequired() {
        assertRejected(() -> anActiveUser().withFullName(null).insert(jdbc), NOT_NULL_VIOLATION);
    }

    // ---- roles & permissions ----

    @Test
    @DisplayName("rejects a second role with the same name")
    void role_nameIsUnique() {
        aRole().withName("ORGANIZER").insert(jdbc);

        assertRejected(() -> aRole().withName("ORGANIZER").insert(jdbc), UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("rejects a second permission with the same code")
    void permission_codeIsUnique() {
        aPermission().withCode("T_DUPLICATE_CODE").insert(jdbc);

        assertRejected(
                () -> aPermission().withCode("T_DUPLICATE_CODE").insert(jdbc), UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("rejects a role without a name")
    void role_nameIsRequired() {
        assertRejected(() -> aRole().withName(null).insert(jdbc), NOT_NULL_VIOLATION);
    }

    @Test
    @DisplayName("rejects a permission without a code")
    void permission_codeIsRequired() {
        assertRejected(() -> aPermission().withCode(null).insert(jdbc), NOT_NULL_VIOLATION);
    }

    // ---- RBAC links: the permission list a JWT will carry is resolved through these ----

    @Test
    @DisplayName("a user's permissions resolve through user_roles then role_permissions")
    void rbac_permissionsResolveThroughRoles() {
        var user = anActiveUser().insert(jdbc);
        var organizer = aRole().insert(jdbc);
        var staff = aRole().insert(jdbc);
        var create = aPermission().withCode("T_CREATE").insert(jdbc);
        var edit = aPermission().withCode("T_EDIT").insert(jdbc);
        var scan = aPermission().withCode("T_SCAN").insert(jdbc);
        aPermission().withCode("T_UNGRANTED").insert(jdbc);
        RbacLinks.grantPermission(jdbc, organizer, create);
        RbacLinks.grantPermission(jdbc, organizer, edit);
        RbacLinks.grantPermission(jdbc, staff, scan);
        RbacLinks.grantPermission(jdbc, staff, edit);
        RbacLinks.assignRole(jdbc, user, organizer);

        List<String> codes =
                jdbc.queryForList(
                        "SELECT DISTINCT p.code FROM user_roles ur"
                                + " JOIN role_permissions rp ON rp.role_id = ur.role_id"
                                + " JOIN permissions p ON p.id = rp.permission_id"
                                + " WHERE ur.user_id = ? ORDER BY p.code",
                        String.class,
                        user);

        assertThat(codes).containsExactly("T_CREATE", "T_EDIT");
    }

    @Test
    @DisplayName("rejects assigning the same role to the same user twice")
    void userRoles_pairIsUnique() {
        var user = anActiveUser().insert(jdbc);
        var role = aRole().insert(jdbc);
        RbacLinks.assignRole(jdbc, user, role);

        assertRejected(() -> RbacLinks.assignRole(jdbc, user, role), UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("rejects granting the same permission to the same role twice")
    void rolePermissions_pairIsUnique() {
        var role = aRole().insert(jdbc);
        var permission = aPermission().insert(jdbc);
        RbacLinks.grantPermission(jdbc, role, permission);

        assertRejected(() -> RbacLinks.grantPermission(jdbc, role, permission), UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("rejects assigning a role to a user that does not exist")
    void userRoles_requiresExistingUser() {
        var role = aRole().insert(jdbc);

        assertRejected(
                () -> RbacLinks.assignRole(jdbc, UUID.randomUUID(), role), FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("rejects granting a permission that does not exist")
    void rolePermissions_requiresExistingPermission() {
        var role = aRole().insert(jdbc);

        assertRejected(
                () -> RbacLinks.grantPermission(jdbc, role, UUID.randomUUID()),
                FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("refuses to delete a role that is still assigned to a user (ON DELETE RESTRICT)")
    void role_inUseCannotBeDeleted() {
        var user = anActiveUser().insert(jdbc);
        var role = aRole().insert(jdbc);
        RbacLinks.assignRole(jdbc, user, role);

        assertRejected(
                () -> jdbc.update("DELETE FROM roles WHERE id = ?", role), FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("refuses to delete a user that still has role assignments (ON DELETE RESTRICT)")
    void user_withRolesCannotBeDeleted() {
        var user = anActiveUser().insert(jdbc);
        var role = aRole().insert(jdbc);
        RbacLinks.assignRole(jdbc, user, role);

        assertRejected(
                () -> jdbc.update("DELETE FROM users WHERE id = ?", user), FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName(
            "refuses to delete a permission that is still granted to a role (ON DELETE RESTRICT)")
    void permission_inUseCannotBeDeleted() {
        var role = aRole().insert(jdbc);
        var permission = aPermission().insert(jdbc);
        RbacLinks.grantPermission(jdbc, role, permission);

        assertRejected(
                () -> jdbc.update("DELETE FROM permissions WHERE id = ?", permission),
                FOREIGN_KEY_VIOLATION);
    }
}
