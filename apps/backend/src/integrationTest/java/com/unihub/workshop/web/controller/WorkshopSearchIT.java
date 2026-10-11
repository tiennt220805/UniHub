package com.unihub.workshop.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import com.unihub.workshop.web.dto.WorkshopSummary;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-05: browse by schedule, search/filter by topic (q), speaker and location, against real
 * Postgres. Rows are committed through plain JDBC, so each test deletes them to keep the shared
 * database empty for the other ITs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopSearchIT extends IntegrationTestSupport {

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

    private void seed(
            String title,
            String description,
            String speaker,
            String location,
            String start,
            String status) {
        var startTs = Timestamp.from(java.time.Instant.parse(start.replace(" ", "T") + "Z"));
        WorkshopRows.insertRaw(
                jdbc,
                Map.of(
                        "title", title,
                        "description", description,
                        "speaker_name", speaker,
                        "location", location,
                        "start_time", startTs,
                        "end_time", new Timestamp(startTs.getTime() + 3_600_000),
                        "status", status));
    }

    private void seedCatalog() {
        seed(
                "Intro to Kubernetes",
                "Containers and pods",
                "Ada Lovelace",
                "Room 101",
                "2026-12-03 09:00:00",
                "PUBLISHED");
        seed(
                "Advanced Postgres",
                "Indexes, locking and Kubernetes operators",
                "Grace Hopper",
                "Hall A",
                "2026-12-01 09:00:00",
                "PUBLISHED");
        seed(
                "Rust for Beginners",
                "Ownership",
                "Ada Byron",
                "Room 202",
                "2026-12-02 09:00:00",
                "PUBLISHED");
        seed(
                "Secret Kubernetes Draft",
                "Not public",
                "Ada Lovelace",
                "Room 101",
                "2026-12-04 09:00:00",
                "DRAFT");
        seed(
                "Cancelled Kubernetes",
                "Gone",
                "Ada Lovelace",
                "Room 101",
                "2026-12-05 09:00:00",
                "CANCELLED");
    }

    private List<String> titles(String query, Object... vars) {
        var response =
                rest.getForEntity("/api/v1/workshops" + query, WorkshopListResponse.class, vars);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().items().stream().map(WorkshopSummary::title).toList();
    }

    @Test
    @DisplayName("browse: returns only PUBLISHED workshops ordered by start time")
    void browse_ordersByStartTime_andHidesNonPublished() {
        seedCatalog();

        assertThat(titles(""))
                .containsExactly("Advanced Postgres", "Rust for Beginners", "Intro to Kubernetes");
    }

    @Test
    @DisplayName("q matches title or description, case-insensitively")
    void q_matchesTitleOrDescription_caseInsensitive() {
        seedCatalog();

        assertThat(titles("?q=KUBERNETES"))
                .containsExactly("Advanced Postgres", "Intro to Kubernetes");
    }

    @Test
    @DisplayName("speaker and location are case-insensitive partial matches, ANDed together")
    void speakerAndLocation_filter() {
        seedCatalog();

        assertThat(titles("?speaker=ada"))
                .containsExactly("Rust for Beginners", "Intro to Kubernetes");
        assertThat(titles("?location={l}", "room 1")).containsExactly("Intro to Kubernetes");
        assertThat(titles("?speaker=ada&location=202")).containsExactly("Rust for Beginners");
        assertThat(titles("?q=postgres&speaker=ada")).isEmpty();
    }

    @Test
    @DisplayName("LIKE wildcards in user input match literally")
    void wildcards_areLiteral() {
        seedCatalog();

        assertThat(titles("?q={q}", "%")).isEmpty();
        assertThat(titles("?speaker={s}", "_da")).isEmpty();
    }

    @Test
    @DisplayName("date range filters on start time, bounds inclusive")
    void dateRange_isInclusive() {
        seedCatalog();

        assertThat(titles("?dateFrom=2026-12-02T09:00:00Z&dateTo=2026-12-03T09:00:00Z"))
                .containsExactly("Rust for Beginners", "Intro to Kubernetes");
        assertThat(titles("?dateFrom=2026-12-03T09:00:00Z")).containsExactly("Intro to Kubernetes");
        assertThat(titles("?dateTo=2026-12-01T09:00:00Z")).containsExactly("Advanced Postgres");
    }

    @Test
    @DisplayName("pagination totals reflect the filtered set, not the whole table")
    void pagination_countsFilteredSet() {
        seedCatalog();

        var body =
                rest.getForEntity(
                                "/api/v1/workshops?speaker=ada&size=1&page=1",
                                WorkshopListResponse.class)
                        .getBody();

        assertThat(body.items())
                .extracting(WorkshopSummary::title)
                .containsExactly("Intro to Kubernetes");
        assertThat(body.page().totalElements()).isEqualTo(2);
        assertThat(body.page().totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("dateFrom after dateTo is a 400 problem+json")
    void invertedDateRange_is400() {
        var response =
                rest.getForEntity(
                        "/api/v1/workshops?dateFrom=2026-12-03T00:00:00Z&dateTo=2026-12-01T00:00:00Z",
                        String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        t ->
                                assertThat(t.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                                        .isTrue());
    }
}
