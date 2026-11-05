package com.unihub.user.infrastructure.in.seeding;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.user.application.command.SeedDevStaffCommand;
import com.unihub.user.application.command.SeedDevStaffCommand.DevStaffAccount;
import com.unihub.user.application.command.SeedDevStaffCommand.StaffRole;
import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.command.SeedDevStudentsCommand.DevStudent;
import com.unihub.user.application.port.in.SeedDevStaffUseCase;
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
 * The {@code dev} seeder end to end (D9; Phase 7 task 5.8 for the staff accounts): the runner
 * creates Students and an Organizer, an Admin and a Check-in Staff account that can really sign in,
 * each with the permissions of its role, and running again creates nothing new. Uses its own emails
 * so the shared database stays clean for the other ITs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevStudentSeedIT extends IntegrationTestSupport {

    private static final String PASSWORD = "dev-seed-" + UUID.randomUUID();
    private static final String EMAIL_A = "seed-a-" + UUID.randomUUID() + "@dev.unihub.local";
    private static final String EMAIL_B = "seed-b-" + UUID.randomUUID() + "@dev.unihub.local";
    private static final String ORGANIZER = "seed-o-" + UUID.randomUUID() + "@dev.unihub.local";
    private static final String ADMIN = "seed-ad-" + UUID.randomUUID() + "@dev.unihub.local";
    private static final String CHECKIN = "seed-c-" + UUID.randomUUID() + "@dev.unihub.local";
    private static final String ORGANIZER_PASSWORD = "org-" + UUID.randomUUID();
    private static final String ADMIN_PASSWORD = "adm-" + UUID.randomUUID();
    private static final String CHECKIN_PASSWORD = "chk-" + UUID.randomUUID();

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
        registry.add("unihub.dev-seed.organizer-password", () -> ORGANIZER_PASSWORD);
        registry.add("unihub.dev-seed.admin-password", () -> ADMIN_PASSWORD);
        registry.add("unihub.dev-seed.checkin-staff-password", () -> CHECKIN_PASSWORD);
        registry.add("unihub.dev-seed.staff[0].email", () -> ORGANIZER);
        registry.add("unihub.dev-seed.staff[0].full-name", () -> "Seeded Organizer");
        registry.add("unihub.dev-seed.staff[0].role", () -> "ORGANIZER");
        registry.add("unihub.dev-seed.staff[1].email", () -> ADMIN);
        registry.add("unihub.dev-seed.staff[1].full-name", () -> "Seeded Admin");
        registry.add("unihub.dev-seed.staff[1].role", () -> "ADMIN");
        registry.add("unihub.dev-seed.staff[2].email", () -> CHECKIN);
        registry.add("unihub.dev-seed.staff[2].full-name", () -> "Seeded Check-in Staff");
        registry.add("unihub.dev-seed.staff[2].role", () -> "CHECKIN_STAFF");
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
        for (var email : List.of(EMAIL_A, EMAIL_B, ORGANIZER, ADMIN, CHECKIN)) {
            deleteAccount(jdbc, email);
        }
    }

    /**
     * A sign-in is audited by a consumer that runs on its own thread, so an {@code audit_events}
     * row naming the account can land between the delete of the rows and the delete of the user
     * (the same race RegistrationFixtures handles): the pair is retried.
     */
    private static void deleteAccount(JdbcTemplate jdbc, String email) {
        for (var attempt = 1; ; attempt++) {
            try {
                jdbc.update(
                        "DELETE FROM audit_events WHERE actor_user_id IN"
                                + " (SELECT id FROM users WHERE email = ?)",
                        email);
                jdbc.update(
                        "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)",
                        email);
                jdbc.update("DELETE FROM users WHERE email = ?", email);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt == 5) {
                    throw e;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;
    @Autowired private SeedDevStudentsUseCase seed;
    @Autowired private SeedDevStaffUseCase seedStaff;
    @Autowired private org.springframework.context.ApplicationContext context;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

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

    // ---------- the staff accounts (Phase 7 task 5.8) ----------

    private List<String> rolesOf(String email) {
        return jdbc.queryForList(
                "SELECT r.name FROM user_roles ur JOIN users u ON u.id = ur.user_id"
                        + " JOIN roles r ON r.id = ur.role_id WHERE u.email = ?",
                String.class,
                email);
    }

    private String loginBody(String email, String password) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response =
                http.postForEntity(
                        "/api/v1/auth/login",
                        new HttpEntity<>(Map.of("email", email, "password", password), headers),
                        String.class);
        assertThat(response.getStatusCode().value()).as("sign-in of %s", email).isEqualTo(200);
        return response.getBody();
    }

    @Test
    @DisplayName("start-up created one account for each staff role, holding that role and no other")
    void runnerSeededTheStaffAccounts() {
        assertThat(rolesOf(ORGANIZER)).containsExactly("Organizer");
        assertThat(rolesOf(ADMIN)).containsExactly("Admin");
        assertThat(rolesOf(CHECKIN)).containsExactly("Check-in Staff");
        for (var email : List.of(ORGANIZER, ADMIN, CHECKIN)) {
            var hash =
                    jdbc.queryForObject(
                            "SELECT password_hash FROM users WHERE email = ?", String.class, email);
            assertThat(hash).startsWith("$2");
        }
    }

    @Test
    @DisplayName(
            "each staff account signs in with its own password and holds the permissions of its role")
    void staffAccountsSignInWithTheirRolesPermissions() {
        assertThat(loginBody(ORGANIZER, ORGANIZER_PASSWORD))
                .contains("WORKSHOP_CREATE", "WORKSHOP_EDIT")
                .doesNotContain("REGISTRATION_CREATE", "CHECKIN_SCAN");
        assertThat(loginBody(ADMIN, ADMIN_PASSWORD))
                .contains("WORKSHOP_CREATE", "REPORT_VIEW")
                .doesNotContain("REGISTRATION_CREATE", "CHECKIN_SCAN");
        assertThat(loginBody(CHECKIN, CHECKIN_PASSWORD))
                .contains("CHECKIN_SCAN")
                .doesNotContain("WORKSHOP_CREATE", "REGISTRATION_CREATE");
    }

    @Test
    @DisplayName("one role's password does not open another role's account")
    void aStaffPasswordIsNotShared() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response =
                http.postForEntity(
                        "/api/v1/auth/login",
                        new HttpEntity<>(
                                Map.of("email", ADMIN, "password", ORGANIZER_PASSWORD), headers),
                        String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("seeding the staff again creates nothing and leaves the accounts as they are")
    void seedingTheStaffTwiceCreatesNothing() {
        var hashBefore =
                jdbc.queryForObject(
                        "SELECT password_hash FROM users WHERE email = ?", String.class, ADMIN);

        var created =
                seedStaff.seed(
                        new SeedDevStaffCommand(
                                List.of(
                                        new DevStaffAccount(
                                                ORGANIZER,
                                                "Seeded Organizer",
                                                StaffRole.ORGANIZER,
                                                "another-password"),
                                        new DevStaffAccount(
                                                ADMIN,
                                                "Seeded Admin",
                                                StaffRole.ADMIN,
                                                "another-password"),
                                        new DevStaffAccount(
                                                CHECKIN,
                                                "Seeded Check-in Staff",
                                                StaffRole.CHECKIN_STAFF,
                                                "another-password"))));

        assertThat(created).isZero();
        assertThat(countOf(ADMIN)).isEqualTo(1);
        assertThat(rolesOf(ADMIN)).containsExactly("Admin");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM users WHERE email = ?",
                                String.class,
                                ADMIN))
                .as("an existing account keeps the password it has")
                .isEqualTo(hashBefore);
    }

    @Test
    @DisplayName(
            "a role that does not exist creates nothing: the account is not left without a role")
    void anUnknownRoleRollsTheAccountBack() {
        var email = "seed-norole-" + UUID.randomUUID() + "@dev.unihub.local";
        // Done through the writer directly: the use case only knows the three real roles.
        var writer = context.getBean(com.unihub.user.application.port.out.DevStaffAccounts.class);
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(txManager);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                writer.createIfAbsent(
                                                        email, "No Role", "HASH", "No Such Role")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No Such Role");

        assertThat(countOf(email)).isZero();
    }
}
