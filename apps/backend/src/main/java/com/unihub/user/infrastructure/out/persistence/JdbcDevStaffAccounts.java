package com.unihub.user.infrastructure.out.persistence;

import com.unihub.user.application.port.out.DevStaffAccounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDevStaffAccounts implements DevStaffAccounts {

    private static final String INSERT_USER =
            "INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?)"
                    + " ON CONFLICT (email) DO NOTHING";

    private static final String ASSIGN_ROLE =
            "INSERT INTO user_roles (user_id, role_id)"
                    + " SELECT u.id, r.id FROM users u, roles r"
                    + " WHERE u.email = ? AND r.name = ?"
                    + " ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    JdbcDevStaffAccounts(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean createIfAbsent(
            String email, String fullName, String passwordHash, String roleName) {
        var created = jdbcTemplate.update(INSERT_USER, email, fullName, passwordHash) == 1;
        if (created && jdbcTemplate.update(ASSIGN_ROLE, email, roleName) != 1) {
            // An account with no role would sign in and be able to do nothing, silently. The seed
            // migration
            // (V2) creates the roles; if it has not run, nothing here is committed (the caller's
            // transaction).
            throw new IllegalStateException("role '" + roleName + "' does not exist");
        }
        return created;
    }
}
