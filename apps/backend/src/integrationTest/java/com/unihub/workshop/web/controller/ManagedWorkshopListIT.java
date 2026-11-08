package com.unihub.workshop.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.dto.WorkshopSummary;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-87 (Phase 8, D119): the management list over real Postgres. Every status is there, {@code
 * DRAFT} included, which the public catalog never returns; it filters by status and title, pages,
 * orders newest start first, and is for {@code WORKSHOP_EDIT} only. Rows are committed through
 * plain JDBC, so each test deletes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagedWorkshopListIT extends IntegrationTestSupport {
    private static final String URL = "/api/v1/admin/workshops";
    private static final UUID USER = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM workshops");
    }

    private void seed(String title, String start, String status) {
        var startTs = Timestamp.from(Instant.parse(start.replace(" ", "T") + "Z"));
        WorkshopRows.insertRaw(
                jdbc,
                Map.of(
                        "title", title,
                        "start_time", startTs,
                        "end_time", new Timestamp(startTs.getTime() + 3_600_000),
                        "status", status));
    }

    private void seedAll() {
        seed("Published early", "2026-12-01 09:00:00", "PUBLISHED");
        seed("Draft middle", "2026-12-02 09:00:00", "DRAFT");
        seed("Cancelled late", "2026-12-03 09:00:00", "CANCELLED");
        seed("Completed first", "2026-11-20 09:00:00", "COMPLETED");
    }

    private ResponseEntity<String> call(String token, String query) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(URL + query, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private WorkshopListResponse list(String query) throws Exception {
        var response =
                rest.exchange(
                        URL + query,
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(TestJwts.valid(USER, "WORKSHOP_EDIT"))),
                        WorkshopListResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private static HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static List<String> titles(WorkshopListResponse response) {
        return response.items().stream().map(WorkshopSummary::title).toList();
    }

    @Test
    @DisplayName("every status is listed, DRAFT included, newest start first")
    void listsEveryStatus_newestFirst() throws Exception {
        seedAll();

        assertThat(titles(list("")))
                .containsExactly(
                        "Cancelled late", "Draft middle", "Published early", "Completed first");
    }

    @Test
    @DisplayName("status filters to one status; a DRAFT is found only here")
    void filtersByStatus() throws Exception {
        seedAll();

        assertThat(titles(list("?status=DRAFT"))).containsExactly("Draft middle");
        assertThat(titles(list("?status=CANCELLED"))).containsExactly("Cancelled late");
        assertThat(titles(list("?status=COMPLETED"))).containsExactly("Completed first");
        assertThat(titles(list("?status=PUBLISHED"))).containsExactly("Published early");
    }

    @Test
    @DisplayName("q is a case-insensitive substring of the title, and a wildcard is not a wildcard")
    void filtersByTitle_escapingWildcards() throws Exception {
        seedAll();
        seed("100% Postgres", "2026-12-10 09:00:00", "DRAFT");

        assertThat(titles(list("?q=DRAFT"))).containsExactly("Draft middle");
        assertThat(titles(list("?q=%&status=DRAFT"))).containsExactly("100% Postgres");
        assertThat(titles(list("?q=_"))).isEmpty();
        assertThat(titles(list("?q=  "))).hasSize(5);
    }

    @Test
    @DisplayName("pages with the totals of the whole result, not of the page")
    void pages() throws Exception {
        seedAll();

        var first = list("?size=3&page=0");
        var second = list("?size=3&page=1");

        assertThat(first.items()).hasSize(3);
        assertThat(second.items()).hasSize(1);
        assertThat(first.page().totalElements()).isEqualTo(4);
        assertThat(first.page().totalPages()).isEqualTo(2);
        assertThat(titles(second)).containsExactly("Completed first");
    }

    @Test
    @DisplayName(
            "a workshop created a moment ago is in the very next response (the Master is read)")
    void seesItsOwnWrite() throws Exception {
        assertThat(list("").items()).isEmpty();

        seed("Just created", "2026-12-01 09:00:00", "DRAFT");

        assertThat(titles(list(""))).containsExactly("Just created");
    }

    @Test
    @DisplayName("an item carries what a card draws, price only when paid, no cover yet")
    void itemShape() throws Exception {
        WorkshopRows.insertRaw(
                jdbc,
                Map.of(
                        "title",
                        "Paid one",
                        "speaker_name",
                        "Ada Lovelace",
                        "capacity",
                        40,
                        "seats_taken",
                        7,
                        "is_paid",
                        true,
                        "price",
                        new java.math.BigDecimal("150000.00"),
                        "status",
                        "DRAFT"));

        var item = list("").items().get(0);

        assertThat(item.speakerName()).isEqualTo("Ada Lovelace");
        assertThat(item.capacity()).isEqualTo(40);
        assertThat(item.seatsTaken()).isEqualTo(7);
        assertThat(item.isPaid()).isTrue();
        assertThat(item.price()).isEqualByComparingTo("150000.00");
        assertThat(item.coverImageUrl()).isNull();
    }

    @Test
    @DisplayName("an unknown status and an oversized page are 400, not an empty list")
    void invalidParameters() {
        var token = TestJwts.valid(USER, "WORKSHOP_EDIT");

        assertThat(call(token, "?status=ARCHIVED").getStatusCode().value()).isEqualTo(400);
        assertThat(call(token, "?size=101").getStatusCode().value()).isEqualTo(400);
        assertThat(call(token, "?page=-1").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName(
            "401 without a token, 403 without WORKSHOP_EDIT (WORKSHOP_CREATE alone is not enough)")
    void permission() {
        seed("Draft", "2026-12-01 09:00:00", "DRAFT");

        assertThat(call(null, "").getStatusCode().value()).isEqualTo(401);
        assertThat(call(TestJwts.valid(USER, "REGISTRATION_CREATE"), "").getStatusCode().value())
                .isEqualTo(403);
        var createOnly = call(TestJwts.valid(USER, "WORKSHOP_CREATE"), "");
        assertThat(createOnly.getStatusCode().value()).isEqualTo(403);
        assertThat(createOnly.getBody()).doesNotContain("Draft");
    }
}
