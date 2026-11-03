package com.unihub.user.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-02 / M-03 / S-9 over real HTTP against {@code POST /api/v1/auth/login}: a token issued by the
 * real endpoint is accepted by the real decoder, carries the permissions its role resolves to, and
 * outlives the suspension of the account that obtained it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginIT extends IntegrationTestSupport {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "correct-horse-battery";
    // Needs WORKSHOP_EDIT, then answers 404 for an unknown id: 401/403/404 tell the three apart.
    private static final String GUARDED = "/api/v1/workshops/" + UUID.randomUUID() + "/cancel";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    private final java.util.List<String> createdEmails = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void deleteCreatedUsers() {
        createdEmails.forEach(
                email -> {
                    jdbc.update(
                            "DELETE FROM user_roles WHERE user_id IN"
                                    + " (SELECT id FROM users WHERE email = ?)",
                            email);
                    jdbc.update("DELETE FROM users WHERE email = ?", email);
                });
    }

    private String createUser(String role, String status, String passwordHash) {
        var email = "login-" + UUID.randomUUID() + "@example.test";
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, full_name, status)"
                        + " VALUES (?, ?, ?, 'Login Tester', ?)",
                id,
                email,
                passwordHash,
                status);
        createdEmails.add(email);
        if (role != null) {
            jdbc.update(
                    "INSERT INTO user_roles (user_id, role_id)"
                            + " SELECT ?, id FROM roles WHERE name = ?",
                    id,
                    role);
        }
        return email;
    }

    private String createActive(String role) {
        return createUser(role, "ACTIVE", bcrypt.encode(PASSWORD));
    }

    // The JDK client, not TestRestTemplate: HttpURLConnection cannot replay a streamed POST body
    // after a 401 and throws instead of returning the response.
    private final HttpClient client = HttpClient.newHttpClient();

    private record Reply(int status, String contentType, String body) {}

    private Reply send(HttpRequest.Builder request) throws Exception {
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private Reply post(String path, String token, String jsonBody) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request);
    }

    private Reply login(String email, String password) throws Exception {
        return post(
                LOGIN, null, json.writeValueAsString(Map.of("email", email, "password", password)));
    }

    private JsonNode body(Reply reply) throws Exception {
        return json.readTree(reply.body());
    }

    private int guardedStatus(String token) throws Exception {
        return post(GUARDED, token, "").status();
    }

    @Test
    @DisplayName("a Student logs in: 200, token, 1800s, user summary and REGISTRATION_CREATE")
    void studentLogin() throws Exception {
        var email = createActive("Student");

        var response = login(email, PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        var body = body(response);
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("expiresIn").asInt()).isEqualTo(1800);
        assertThat(body.get("user").get("email").asText()).isEqualTo(email);
        assertThat(body.get("user").get("fullName").asText()).isEqualTo("Login Tester");
        assertThat(body.get("user").get("roles"))
                .extracting(JsonNode::asText)
                .containsExactly("Student");
        assertThat(body.get("permissions"))
                .extracting(JsonNode::asText)
                .containsExactly("REGISTRATION_CREATE");
    }

    @Test
    @DisplayName("only the Student role resolves to REGISTRATION_CREATE (AC7.2)")
    void permissionsPerRole() throws Exception {
        assertThat(permissionsOf("Organizer"))
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT", "REPORT_VIEW");
        assertThat(permissionsOf("Admin"))
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT", "REPORT_VIEW");
        assertThat(permissionsOf("Check-in Staff")).containsExactly("CHECKIN_SCAN");
    }

    private java.util.List<String> permissionsOf(String role) throws Exception {
        var body = body(login(createActive(role), PASSWORD));
        return java.util.stream.StreamSupport.stream(body.get("permissions").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    @Test
    @DisplayName("a user with no role logs in with an empty permission list")
    void noRole() throws Exception {
        var body = body(login(createActive(null), PASSWORD));

        assertThat(body.get("permissions")).isEmpty();
        assertThat(body.get("user").get("roles")).isEmpty();
    }

    @Test
    @DisplayName(
            "the issued token is honoured by the real filter chain, per its permissions (M-02, M-03)")
    void issuedTokenIsEnforced() throws Exception {
        var organizer =
                body(login(createActive("Organizer"), PASSWORD)).get("accessToken").asText();
        var student = body(login(createActive("Student"), PASSWORD)).get("accessToken").asText();

        assertThat(guardedStatus(null)).isEqualTo(401);
        assertThat(guardedStatus(student)).isEqualTo(403);
        assertThat(guardedStatus(organizer)).isEqualTo(404);
    }

    @Test
    @DisplayName("wrong password, unknown email, no password yet: one identical 401 problem")
    void refusalsAreIndistinguishable() throws Exception {
        var email = createActive("Student");
        var legacy = createUser("Student", "ACTIVE", null);

        var wrongPassword = login(email, "nope");
        var unknownEmail = login("nobody-" + UUID.randomUUID() + "@example.test", PASSWORD);
        var noPasswordYet = login(legacy, PASSWORD);

        for (var refusal : java.util.List.of(wrongPassword, unknownEmail, noPasswordYet)) {
            assertThat(refusal.status()).isEqualTo(401);
            assertThat(refusal.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
        // The correlationId is the request's own trace id, so it differs between the three by
        // design; everything else must be the same bytes.
        assertThat(withoutCorrelationId(unknownEmail.body()))
                .isEqualTo(withoutCorrelationId(wrongPassword.body()));
        assertThat(withoutCorrelationId(noPasswordYet.body()))
                .isEqualTo(withoutCorrelationId(wrongPassword.body()));
    }

    private static String withoutCorrelationId(String problem) {
        return problem.replaceAll(",\\s*\"correlationId\"\\s*:\\s*\"[0-9a-f]{32}\"", "");
    }

    @Test
    @DisplayName(
            "S-9: a SUSPENDED user cannot log in; a token issued earlier works until it expires")
    void suspendedUser() throws Exception {
        var email = createActive("Organizer");
        var token = body(login(email, PASSWORD)).get("accessToken").asText();
        assertThat(guardedStatus(token)).isEqualTo(404);

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        var fresh = login(email, PASSWORD);
        assertThat(fresh.status()).isEqualTo(401);
        assertThat(fresh.body()).doesNotContain("SUSPENDED").doesNotContain("suspended");
        assertThat(guardedStatus(token)).isEqualTo(404);
    }

    @Test
    @DisplayName("a malformed email or a missing field is a 400 problem, not a 401")
    void validationFailures() throws Exception {
        var badEmail = post(LOGIN, null, "{\"email\":\"not-an-email\",\"password\":\"x\"}");
        var noPassword = post(LOGIN, null, "{\"email\":\"a@example.test\"}");

        assertThat(badEmail.status()).isEqualTo(400);
        assertThat(noPassword.status()).isEqualTo(400);
        assertThat(badEmail.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    @Test
    @DisplayName("the password is not echoed anywhere in a response")
    void passwordNeverEchoed() throws Exception {
        var email = createActive("Student");

        assertThat(login(email, PASSWORD).body()).doesNotContain(PASSWORD);
        assertThat(login(email, "wrong-" + PASSWORD).body()).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a password far over BCrypt's 72 bytes is a plain 401 problem, never a 500")
    void overlongPassword() throws Exception {
        var email = createActive("Student");

        var response = login(email, "x".repeat(100));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }
}
