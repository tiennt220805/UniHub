package com.unihub.user.infrastructure.out.persistence;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.application.port.out.LoginAccounts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLoginAccounts implements LoginAccounts {

    private static final String BY_EMAIL =
            "SELECT id, email, full_name, password_hash, status FROM users WHERE email = ?";

    private final JdbcTemplate jdbcTemplate;

    JdbcLoginAccounts(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<LoginAccount> findByEmail(String email) {
        return jdbcTemplate
                .query(
                        BY_EMAIL,
                        (rs, row) ->
                                new LoginAccount(
                                        UserId.of(rs.getObject("id", UUID.class)),
                                        rs.getString("email"),
                                        rs.getString("full_name"),
                                        rs.getString("password_hash"),
                                        "ACTIVE".equals(rs.getString("status"))),
                        email)
                .stream()
                .findFirst();
    }
}
