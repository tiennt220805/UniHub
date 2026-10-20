package com.unihub.user.infrastructure.out.persistence;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.application.port.out.UserRoleQueries;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserRoleQueries implements UserRoleQueries {

    private static final String ROLE_NAMES_OF_USER =
            "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                    + " WHERE ur.user_id = ? ORDER BY r.name";

    private final JdbcTemplate jdbcTemplate;

    JdbcUserRoleQueries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> roleNamesOf(UserId userId) {
        return jdbcTemplate.queryForList(ROLE_NAMES_OF_USER, String.class, userId.value());
    }
}
