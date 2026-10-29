package com.unihub.user.infrastructure.out.persistence;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.api.UserContact;
import com.unihub.user.application.port.out.UserContactQueries;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserContactQueries implements UserContactQueries {

    private static final String SELECT = "SELECT id, full_name, email FROM users WHERE ";

    private static final RowMapper<UserContact> CONTACT =
            (rs, rowNum) ->
                    new UserContact(
                            UserId.of(rs.getObject("id", UUID.class)),
                            rs.getString("full_name"),
                            rs.getString("email"));

    private final NamedParameterJdbcTemplate jdbc;

    JdbcUserContactQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserContact> findById(UserId id) {
        return jdbc
                .query(SELECT + "id = :id", new MapSqlParameterSource("id", id.value()), CONTACT)
                .stream()
                .findFirst();
    }

    @Override
    public Map<UserId, UserContact> findAllById(Collection<UserId> ids) {
        var found = new LinkedHashMap<UserId, UserContact>();
        var values = ids.stream().map(UserId::value).distinct().toList();
        jdbc.query(SELECT + "id IN (:ids)", new MapSqlParameterSource("ids", values), CONTACT)
                .forEach(contact -> found.put(contact.id(), contact));
        return found;
    }
}
