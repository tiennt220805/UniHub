package com.unihub.workshop.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** M-06: GET /workshops/{id} against real Postgres — detail fields, seats, DRAFT visibility. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopDetailIT extends IntegrationTestSupport {

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

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedRows() {
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
    }

    private UUID insert(Workshop workshop) {
        created.add(workshop.id().value());
        WorkshopRows.insert(jdbc, workshop);
        return workshop.id().value();
    }

    private ResponseEntity<String> get(UUID id, String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(
                "/api/v1/workshops/" + id, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static void assertProblem(ResponseEntity<String> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(
                        response.getHeaders()
                                .getContentType()
                                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
    }

    @Test
    @DisplayName("a PUBLISHED workshop is public and shows speaker, location and seats taken")
    void published_showsSpeakerLocationSeats() throws Exception {
        var id =
                insert(
                        aPublishedWorkshop()
                                .withSpeakerName("Grace Hopper")
                                .withLocation("Hall A")
                                .withCapacity(40)
                                .withSeatsTaken(7)
                                .build());

        var response = get(id, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("speakerName").asText()).isEqualTo("Grace Hopper");
        assertThat(body.get("location").asText()).isEqualTo("Hall A");
        assertThat(body.get("capacity").asInt()).isEqualTo(40);
        assertThat(body.get("seatsTaken").asInt()).isEqualTo(7);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName(
            "seat availability is not stale once its cache entry is gone: the read shows seats_taken now")
    void seats_areFresh() throws Exception {
        var id = insert(aPublishedWorkshop().withCapacity(40).withSeatsTaken(1).build());
        assertThat(JSON.readTree(get(id, null).getBody()).get("seatsTaken").asInt()).isEqualTo(1);

        jdbc.update("UPDATE workshops SET seats_taken = 9 WHERE id = ?", id);
        // Redis is live in every context (D21), so the first read cached seats for up to 3 s. A
        // write
        // through the application drops that entry; this test writes the row directly and does it
        // itself.
        flushRedis();

        assertThat(JSON.readTree(get(id, null).getBody()).get("seatsTaken").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("a CANCELLED workshop is still publicly visible")
    void cancelled_isVisible() {
        var id = insert(aCancelledWorkshop().build());

        assertThat(get(id, null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a DRAFT is 404 to anonymous and to callers without a management permission")
    void draft_hiddenFromUnauthorised() {
        var id = insert(aDraftWorkshop().build());

        assertProblem(get(id, null), 404);
        assertProblem(get(id, TestJwts.valid(UUID.randomUUID(), "CHECKIN_SCAN")), 404);
        assertProblem(get(id, TestJwts.valid(UUID.randomUUID())), 404);
    }

    @Test
    @DisplayName("a DRAFT is visible to WORKSHOP_CREATE and WORKSHOP_EDIT holders")
    void draft_visibleToManagers() {
        var id = insert(aDraftWorkshop().build());

        assertThat(
                        get(id, TestJwts.valid(UUID.randomUUID(), "WORKSHOP_CREATE"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        get(id, TestJwts.valid(UUID.randomUUID(), "WORKSHOP_EDIT"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a missing workshop is the same 404 problem+json as a hidden draft")
    void unknown_is404() {
        assertProblem(get(UUID.randomUUID(), null), 404);
    }
}
