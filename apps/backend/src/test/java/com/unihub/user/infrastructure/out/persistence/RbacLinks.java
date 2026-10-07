package com.unihub.user.infrastructure.out.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-support for the {@code user_roles} and {@code role_permissions} join tables. */
public final class RbacLinks {

    private RbacLinks() {}

    public static void assignRole(JdbcTemplate jdbc, UUID userId, UUID roleId) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }

    public static void grantPermission(JdbcTemplate jdbc, UUID roleId, UUID permissionId) {
        jdbc.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId,
                permissionId);
    }
}
