package com.unihub.notification.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.notification.application.port.out.NewNotification;
import com.unihub.notification.application.port.out.NotificationRepository;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The inbox over real HTTP and real Postgres (Phase 4 checklist D38: AC38.1, AC38.2): only the
 * caller's own {@code IN_APP} rows, newest first, paginated, and never another user's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListMyNotificationsIT extends IntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationRepository repository;

    private final List<UUID> users = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        users.forEach(
                user -> {
                    jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
                    jdbc.update("DELETE FROM users WHERE id = ?", user);
                });
    }

    private UUID student() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, 'Student')",
                id,
                id + "@example.test");
        users.add(id);
        return id;
    }

    /** One in-app row, optionally already SENT, created {@code minutesAgo} minutes ago. */
    private UUID inApp(UUID user, String title, String type, int minutesAgo, boolean sent) {
        repository.insertIfAbsent(
                List.of(
                        new NewNotification(
                                UUID.randomUUID(),
                                user,
                                "IN_APP",
                                type,
                                "WORKSHOP",
                                UUID.randomUUID(),
                                title,
                                "Body of " + title)));
        var id =
                jdbc.queryForObject(
                        "SELECT id FROM notifications WHERE user_id = ? AND title = ?",
                        UUID.class,
                        user,
                        title);
        jdbc.update(
                "UPDATE notifications SET created_at = now() - make_interval(mins => ?) WHERE id = ?",
                minutesAgo,
                id);
        if (sent) {
            repository.markSent(id);
        }
        return id;
    }

    private JsonNode inbox(UUID caller, String query) throws Exception {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(caller));
        var response =
                http.exchange(
                        "/api/v1/notifications" + query,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JSON.readTree(response.getBody());
    }

    @Test
    @DisplayName(
            "the caller sees only their own in-app rows, newest first, with stored title and body (AC38.1)")
    void inbox_isOwnInAppNewestFirst() throws Exception {
        var ann = student();
        inApp(ann, "oldest", "WORKSHOP_CANCELLED", 30, true);
        inApp(ann, "newest", "WORKSHOP_RESCHEDULED", 1, true);
        inApp(ann, "middle", "REGISTRATION_CONFIRMED", 10, false);
        // an EMAIL row of the same user never appears
        repository.insertIfAbsent(
                List.of(
                        new NewNotification(
                                UUID.randomUUID(),
                                ann,
                                "EMAIL",
                                "WORKSHOP_CANCELLED",
                                "WORKSHOP",
                                UUID.randomUUID(),
                                null,
                                null)));

        var body = inbox(ann, "");

        assertThat(body.at("/items")).hasSize(3);
        assertThat(body.at("/items/0/title").asText()).isEqualTo("newest");
        assertThat(body.at("/items/1/title").asText()).isEqualTo("middle");
        assertThat(body.at("/items/2/title").asText()).isEqualTo("oldest");
        assertThat(body.at("/items/0/body").asText()).isEqualTo("Body of newest");
        assertThat(body.at("/items/0/type").asText()).isEqualTo("WORKSHOP_RESCHEDULED");
        assertThat(body.at("/items/0/status").asText()).isEqualTo("SENT");
        assertThat(body.at("/items/0/sentAt").isTextual()).isTrue();
        assertThat(body.at("/items/1/status").asText()).isEqualTo("PENDING");
        assertThat(body.at("/items/1/sentAt").isNull()).isTrue();
        assertThat(body.at("/page/totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("one student never sees another's notifications, with either token (AC38.2)")
    void inbox_isNeverSharedBetweenUsers() throws Exception {
        var ann = student();
        var bob = student();
        inApp(ann, "for ann", "WORKSHOP_CANCELLED", 5, true);
        inApp(bob, "for bob", "WORKSHOP_CANCELLED", 5, true);

        var annsView = inbox(ann, "");
        var bobsView = inbox(bob, "?userId=" + ann);

        assertThat(annsView.at("/items")).hasSize(1);
        assertThat(annsView.at("/items/0/title").asText()).isEqualTo("for ann");
        assertThat(bobsView.at("/items")).hasSize(1);
        assertThat(bobsView.at("/items/0/title").asText())
                .as("a userId parameter changes nothing")
                .isEqualTo("for bob");
    }

    @Test
    @DisplayName("pages are bounded and stable; the status filter narrows the list")
    void inbox_pagesAndFilters() throws Exception {
        var ann = student();
        for (var i = 0; i < 5; i++) {
            inApp(ann, "n" + i, "WORKSHOP_CANCELLED", 50 - i, i % 2 == 0);
        }

        var first = inbox(ann, "?page=0&size=2");
        var third = inbox(ann, "?page=2&size=2");
        var sentOnly = inbox(ann, "?status=SENT");

        assertThat(first.at("/items")).hasSize(2);
        assertThat(first.at("/page/totalElements").asLong()).isEqualTo(5);
        assertThat(first.at("/page/totalPages").asInt()).isEqualTo(3);
        assertThat(third.at("/items")).hasSize(1);
        assertThat(sentOnly.at("/items")).hasSize(3);
        assertThat(sentOnly.at("/items"))
                .allSatisfy(item -> assertThat(item.get("status").asText()).isEqualTo("SENT"));
    }

    @Test
    @DisplayName("an empty inbox is an empty page, not an error")
    void inbox_emptyIsFine() throws Exception {
        var body = inbox(student(), "");

        assertThat(body.at("/items")).isEmpty();
        assertThat(body.at("/page/totalElements").asLong()).isZero();
        assertThat(body.at("/page/totalPages").asInt()).isZero();
    }

    @Test
    @DisplayName("without a token it is 401")
    void inbox_needsAToken() {
        var response = http.getForEntity("/api/v1/notifications", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
