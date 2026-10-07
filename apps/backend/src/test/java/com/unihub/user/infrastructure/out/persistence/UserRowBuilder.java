package com.unihub.user.infrastructure.out.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test Data Builder for a {@code users} row (User is Tier B — transaction script, no aggregate,
 * backend-coding-standards.md §9.1 — so the fixture is a row, not a domain object). Baseline {@link
 * #anActiveUser()}: random email, password hash set, {@code legacyStudentId = null}, {@code status
 * = ACTIVE} (test-data-management.md §2). Email and legacy id are random by default so
 * parallel/repeated tests never collide on the UNIQUE constraints (§5).
 */
public final class UserRowBuilder {

    private UUID id = UUID.randomUUID();
    private String email = "user-" + UUID.randomUUID() + "@example.test";
    private String passwordHash = "{bcrypt}$2a$10$fixture-hash-not-a-real-credential";
    private String fullName = "Test User";
    private String legacyStudentId;
    private String status = "ACTIVE";

    private UserRowBuilder() {}

    /** A directly-provisioned active account (not legacy-synced). */
    public static UserRowBuilder anActiveUser() {
        return new UserRowBuilder();
    }

    public static UserRowBuilder aSuspendedUser() {
        return new UserRowBuilder().withStatus("SUSPENDED");
    }

    /** A legacy-synced student: no local password yet, a legacy student id set. */
    public static UserRowBuilder aLegacyStudent() {
        return new UserRowBuilder()
                .withPasswordHash(null)
                .withLegacyStudentId("SV" + UUID.randomUUID().toString().substring(0, 8));
    }

    public UserRowBuilder withId(UUID id) {
        this.id = id;
        return this;
    }

    public UserRowBuilder withEmail(String email) {
        this.email = email;
        return this;
    }

    public UserRowBuilder withPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        return this;
    }

    public UserRowBuilder withFullName(String fullName) {
        this.fullName = fullName;
        return this;
    }

    public UserRowBuilder withLegacyStudentId(String legacyStudentId) {
        this.legacyStudentId = legacyStudentId;
        return this;
    }

    public UserRowBuilder withStatus(String status) {
        this.status = status;
        return this;
    }

    /** Inserts the row and returns its id. */
    public UUID insert(JdbcTemplate jdbc) {
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, full_name, legacy_student_id, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                email,
                passwordHash,
                fullName,
                legacyStudentId,
                status);
        return id;
    }
}
