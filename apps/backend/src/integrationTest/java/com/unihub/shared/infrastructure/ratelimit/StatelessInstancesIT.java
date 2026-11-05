package com.unihub.shared.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.MultiInstanceHarness;
import com.unihub.shared.infrastructure.MultiInstanceHarness.Locking;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * M-68: the instances share nothing but the database and Redis — no session (Phase 7 checklist 3.6;
 * auth-and-identity.md §3). A token issued by instance A is accepted by B; a write through A is
 * read through B; and neither instance ever creates an HTTP session (the chain is {@code
 * STATELESS}: its security context lives in a request attribute, never in a session, and no
 * response sets a session cookie). If a session existed, a second pod would not know the caller.
 */
class StatelessInstancesIT extends IntegrationTestSupport {

    private static final String PASSWORD = "correct-horse-battery";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String CREATE_BODY =
            """
            {"title":"Stateless check","description":"Written through A, read through B.",
             "speakerName":"Ada Lovelace","location":"Room 101",
             "startTime":"2026-12-01T09:00:00Z","endTime":"2026-12-01T11:00:00Z",
             "capacity":50,"isPaid":false}""";

    private static MultiInstanceHarness instances;
    private static JdbcTemplate jdbc;
    private static UUID userId;
    private static String email;
    private static UUID workshopId;

    @BeforeAll
    static void startTwoInstances() {
        instances = MultiInstanceHarness.start(2, Locking.REDISSON, Duration.ZERO);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        userId = UUID.randomUUID();
        email = "stateless-" + userId + "@example.test";
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, full_name, status)"
                        + " VALUES (?, ?, ?, 'Stateless Tester', 'ACTIVE')",
                userId,
                email,
                new BCryptPasswordEncoder().encode(PASSWORD));
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id)"
                        + " SELECT ?, id FROM roles WHERE name = 'Organizer'",
                userId);
    }

    @AfterAll
    static void stopInstances() {
        instances.close();
        if (workshopId != null) {
            jdbc.update("DELETE FROM workshops WHERE id = ?", workshopId);
        }
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(int instance, String path) {
        return HttpRequest.newBuilder(
                URI.create("http://localhost:" + instances.port(instance) + path));
    }

    private static void assertNoSession(HttpResponse<?> response) {
        assertThat(response.headers().allValues("Set-Cookie"))
                .as("no session cookie in %s", response.uri())
                .noneMatch(c -> c.toUpperCase().contains("JSESSIONID"));
    }

    @Test
    @DisplayName(
            "a token issued at instance 0 is accepted at instance 1; what 0 writes, 1 reads; no session anywhere")
    void tokenAndDataCrossInstances_withoutASession() throws Exception {
        // issued by A
        var login =
                send(
                        request(0, "/api/v1/auth/login")
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                        .formatted(email, PASSWORD)))
                                .build());
        assertThat(login.statusCode()).isEqualTo(200);
        assertNoSession(login);
        var token = JSON.readTree(login.body()).get("accessToken").asText();

        // written through A ...
        var created =
                send(
                        request(0, "/api/v1/workshops")
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(CREATE_BODY))
                                .build());
        assertThat(created.statusCode()).isEqualTo(201);
        assertNoSession(created);
        JsonNode workshop = JSON.readTree(created.body());
        workshopId = UUID.fromString(workshop.get("id").asText());

        // ... read through B, with the token A issued (a DRAFT is visible only to a manager's
        // token)
        var read =
                send(
                        request(1, "/api/v1/workshops/" + workshopId)
                                .header("Authorization", "Bearer " + token)
                                .GET()
                                .build());
        assertThat(read.statusCode()).isEqualTo(200);
        assertNoSession(read);
        assertThat(JSON.readTree(read.body()).get("title").asText()).isEqualTo("Stateless check");

        // and the token alone is the identity: B refuses the same call without it
        var anonymous = send(request(1, "/api/v1/workshops/" + workshopId).GET().build());
        assertThat(anonymous.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName(
            "each instance's API security chain keeps the security context in the request, never in"
                    + " an HTTP session")
    void securityChain_isStateless() {
        for (int i = 0; i < instances.instances(); i++) {
            // two chains exist: the management port's and the API's (the one that verifies bearer
            // tokens)
            var api =
                    instances.context(i).getBeansOfType(SecurityFilterChain.class).values().stream()
                            .filter(
                                    c ->
                                            c.getFilters().stream()
                                                    .anyMatch(
                                                            BearerTokenAuthenticationFilter.class
                                                                    ::isInstance))
                            .toList();
            assertThat(api).as("instance %d has one API chain", i).hasSize(1);
            var holder =
                    api.get(0).getFilters().stream()
                            .filter(SecurityContextHolderFilter.class::isInstance)
                            .findFirst()
                            .orElseThrow();

            assertThat(ReflectionTestUtils.getField(holder, "securityContextRepository"))
                    .as("instance %d", i)
                    .isInstanceOf(RequestAttributeSecurityContextRepository.class);
        }
    }
}
