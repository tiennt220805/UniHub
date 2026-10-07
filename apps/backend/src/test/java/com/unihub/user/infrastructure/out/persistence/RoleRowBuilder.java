package com.unihub.user.infrastructure.out.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test Data Builder for a {@code roles} row. The name is random by default (UNIQUE column). */
public final class RoleRowBuilder {

    private UUID id = UUID.randomUUID();
    private String name = "role-" + UUID.randomUUID();
    private String description = "Test role";

    private RoleRowBuilder() {}

    public static RoleRowBuilder aRole() {
        return new RoleRowBuilder();
    }

    public RoleRowBuilder withId(UUID id) {
        this.id = id;
        return this;
    }

    public RoleRowBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public RoleRowBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public UUID insert(JdbcTemplate jdbc) {
        jdbc.update(
                "INSERT INTO roles (id, name, description) VALUES (?, ?, ?)",
                id,
                name,
                description);
        return id;
    }
}
