package com.unihub.user.infrastructure.in.seeding;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.command.SeedDevStudentsCommand.DevStudent;
import com.unihub.user.application.port.in.SeedDevStudentsUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The {@code dev} seeder end to end (D9): the runner creates Students that can really sign in, and
 * running again creates nothing new. Uses its own emails so the shared database stays clean for the
 * other ITs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevStudentSeedIT extends IntegrationTestSupport {

    private static final String PASSWORD = "dev-seed-" + UUID.randomUUID();
    private static final String EMAIL_A = "seed-a-" + UUID.randomUUID() + "@dev.unihub.local";
    private static final String EMAIL_B = "seed-b-" + UUID.randomUUID() + "@dev.unihub.local";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.dev-seed.student-password", () -> PASSWORD);
        registry.add("unihub.dev-seed.students[0].email", () -> EMAIL_A);
        registry.add("unihub.dev-seed.students[0].full-name", () -> "Seeded A");
        registry.add("unihub.dev-seed.students[1].email", () -> EMAIL_B);
        registry.add("unihub.dev-seed.students[1].full-name", () -> "Seeded B");
    }

    // The runner commits these rows at start-up; the Postgres container is reused across runs.
    @org.junit.jupiter.api.AfterAll
    static void deleteSeededAccounts() {
        var jdbc =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        for (var email : List.of(EMAIL_A, EMAIL_B)) {
            jdbc.update(
                    "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)",
                    email);
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;
    @Autowired private SeedDevStudentsUseCase seed;

    private long countOf(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email);
    }

    @Test
    @DisplayName(
            "start-up created the Student accounts: Student role, BCrypt hash, never the plaintext")
    void runnerSeededAccounts() {
        for (var email : List.of(EMAIL_A, EMAIL_B)) {
            assertThat(countOf(email)).isEqualTo(1);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT r.name FROM user_roles ur JOIN users u ON u.id = ur.user_id"
                                            + " JOIN roles r ON r.id = ur.role_id WHERE u.email = ?",
                                    String.class,
                                    email))
                    .containsExactly("Student");
            var hash =
                    jdbc.queryForObject(
                            "SELECT password_hash FROM users WHERE email = ?", String.class, email);
            assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        }
    }

    @Test
    @DisplayName("a seeded Student can sign in and holds REGISTRATION_CREATE")
    void seededStudentCanLogin() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response =
                http.postForEntity(
                        "/api/v1/auth/login",
                        new HttpEntity<>(Map.of("email", EMAIL_A, "password", PASSWORD), headers),
                        String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("REGISTRATION_CREATE");
    }

    @Test
    @DisplayName(
            "seeding again creates nothing new and leaves an existing account untouched (AC: idempotent)")
    void seedingTwiceCreatesNothing() {
        var hashBefore =
                jdbc.queryForObject(
                        "SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL_A);

        var created =
                seed.seed(
                        new SeedDevStudentsCommand(
                                List.of(
                                        new DevStudent(EMAIL_A, "Seeded A"),
                                        new DevStudent(EMAIL_B, "Seeded B")),
                                PASSWORD));

        assertThat(created).isZero();
        assertThat(countOf(EMAIL_A)).isEqualTo(1);
        assertThat(countOf(EMAIL_B)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM users WHERE email = ?",
                                String.class,
                                EMAIL_A))
                .isEqualTo(hashBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id"
                                        + " WHERE u.email = ?",
                                Long.class,
                                EMAIL_A))
                .isEqualTo(1);
    }
}
