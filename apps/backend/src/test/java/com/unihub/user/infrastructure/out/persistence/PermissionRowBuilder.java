package com.unihub.user.infrastructure.out.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test Data Builder for a {@code permissions} row. The code is random by default (UNIQUE). */
public final class PermissionRowBuilder {

    private UUID id = UUID.randomUUID();
    private String code = "PERM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private String description = "Test permission";

    private PermissionRowBuilder() {}

    public static PermissionRowBuilder aPermission() {
        return new PermissionRowBuilder();
    }

    public PermissionRowBuilder withId(UUID id) {
        this.id = id;
        return this;
    }

    public PermissionRowBuilder withCode(String code) {
        this.code = code;
        return this;
    }

    public PermissionRowBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public UUID insert(JdbcTemplate jdbc) {
        jdbc.update(
                "INSERT INTO permissions (id, code, description) VALUES (?, ?, ?)",
                id,
                code,
                description);
        return id;
    }
}
