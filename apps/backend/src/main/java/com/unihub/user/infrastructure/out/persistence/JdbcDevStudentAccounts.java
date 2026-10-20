package com.unihub.user.infrastructure.out.persistence;

import com.unihub.user.application.port.out.DevStudentAccounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDevStudentAccounts implements DevStudentAccounts {

    private static final String INSERT_USER =
            "INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?)"
                    + " ON CONFLICT (email) DO NOTHING";

    private static final String ASSIGN_STUDENT_ROLE =
            "INSERT INTO user_roles (user_id, role_id)"
                    + " SELECT u.id, r.id FROM users u, roles r"
                    + " WHERE u.email = ? AND r.name = 'Student'"
                    + " ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    JdbcDevStudentAccounts(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean createIfAbsent(String email, String fullName, String passwordHash) {
        var created = jdbcTemplate.update(INSERT_USER, email, fullName, passwordHash) == 1;
        if (created) {
            jdbcTemplate.update(ASSIGN_STUDENT_ROLE, email);
        }
        return created;
    }
}
