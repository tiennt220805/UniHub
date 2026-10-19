package com.unihub.workshop.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-08, the "cache down" half (distributed-coordination.md §4): with Redis configured but
 * unreachable, every workshop read and write still succeeds against the database. Nothing listens
 * on the configured port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopCacheRedisDownIT extends IntegrationTestSupport {

    private static final UUID USER = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/workshops";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.redis.address", () -> "redis://127.0.0.1:1");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void allowPatch() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
    }

    private UUID insert(Workshop workshop) {
        created.add(workshop.id().value());
        WorkshopRows.insert(jdbc, workshop);
        return workshop.id().value();
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, String body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String editor() {
        return TestJwts.valid(USER, "WORKSHOP_EDIT");
    }

    @Test
    @DisplayName("reading a workshop works, straight from the database")
    void detail_isServedFromTheDatabase() throws Exception {
        var id = insert(aPublishedWorkshop().withSeatsTaken(3).build());

        var response = send(HttpMethod.GET, BASE + "/" + id, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(response.getBody()).get("seatsTaken").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("every read sees the database as it is now: there is nothing cached to be stale")
    void reads_areAlwaysCurrent() throws Exception {
        var id = insert(aPublishedWorkshop().withCapacity(40).withSeatsTaken(1).build());
        assertThat(JSON.readTree(send(HttpMethod.GET, BASE + "/" + id, null, null).getBody()))
                .isNotNull();

        jdbc.update("UPDATE workshops SET seats_taken = 9 WHERE id = ?", id);

        var response = send(HttpMethod.GET, BASE + "/" + id, null, null);
        assertThat(JSON.readTree(response.getBody()).get("seatsTaken").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("searching works")
    void search_works() {
        insert(aPublishedWorkshop().build());

        var response = send(HttpMethod.GET, BASE, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("editing and cancelling work: a failed invalidation never fails the write")
    void writes_work() {
        var id = insert(aPublishedWorkshop().build());

        var patch = send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"title\":\"Renamed\"}");
        var cancel = send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);

        assertThat(patch.getStatusCode().value()).isEqualTo(200);
        assertThat(cancel.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a dead Redis costs a bounded delay, not seconds per request")
    void deadRedis_isFast() {
        var id = insert(aPublishedWorkshop().build());
        send(HttpMethod.GET, BASE + "/" + id, null, null); // takes the one failed attempt

        var started = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            send(HttpMethod.GET, BASE + "/" + id, null, null);
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
}
