package com.unihub.user.infrastructure.out.persistence;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.application.port.out.UserPermissionQueries;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Pure data lookup — reconfiguring roles is a write to these tables, never a code change. */
@Repository
class JdbcUserPermissionQueries implements UserPermissionQueries {

    private static final String PERMISSION_CODES_OF_USER =
            "SELECT DISTINCT p.code FROM user_roles ur"
                    + " JOIN role_permissions rp ON rp.role_id = ur.role_id"
                    + " JOIN permissions p ON p.id = rp.permission_id"
                    + " WHERE ur.user_id = ?";

    private final JdbcTemplate jdbcTemplate;

    JdbcUserPermissionQueries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Set<String> permissionCodesOf(UserId userId) {
        return Set.copyOf(
                jdbcTemplate.queryForList(PERMISSION_CODES_OF_USER, String.class, userId.value()));
    }
}
