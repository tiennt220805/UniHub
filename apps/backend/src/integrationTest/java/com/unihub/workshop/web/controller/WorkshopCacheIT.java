package com.unihub.workshop.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.api.WorkshopCacheInvalidation;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
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
 * M-08 (REQ-2.4.1, REQ-2.4.2) end to end, on real Redis and PostgreSQL: low-change workshop data is
 * served from the cache, and refreshed when its source changes (workshop.md §2).
 *
 * <p>Proving a hit needs a database change the application does not know about, so these tests
 * change rows with SQL — the cache, by design, cannot see that — and read what the API says.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopCacheIT extends IntegrationTestSupport {

    private static final UUID USER = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/workshops";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add(
                "unihub.redis.address",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedissonClient redis;
    @Autowired private WorkshopCacheInvalidation seatInvalidation;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // The default HttpURLConnection-based factory cannot send PATCH.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        redis.getKeys().flushdb();
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
        redis.getKeys().flushdb();
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

    private JsonNode get(UUID id) throws Exception {
        var response = send(HttpMethod.GET, BASE + "/" + id, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JSON.readTree(response.getBody());
    }

    private static String editor() {
        return TestJwts.valid(USER, "WORKSHOP_EDIT");
    }

    private boolean cached(String kind, UUID id) {
        // The detail entry carries the shape version (WorkshopCacheKeys.SHAPE); seats do not.
        var versioned = kind.equals("detail") ? kind + ":v2" : kind;
        return redis.getBucket(
                        "unihub:cache:workshop:" + versioned + ":" + id, StringCodec.INSTANCE)
                .isExists();
    }

    // ---------- served from the cache ----------

    @Test
    @DisplayName("the first read fills the detail and seats entries")
    void firstRead_fillsTheCache() throws Exception {
        var id = insert(aPublishedWorkshop().build());
        assertThat(cached("detail", id)).isFalse();

        get(id);

        assertThat(cached("detail", id)).isTrue();
        assertThat(cached("seats", id)).isTrue();
    }

    @Test
    @DisplayName(
            "a later read is served from the cache: a change the app does not know about is not seen")
    void secondRead_isServedFromTheCache() throws Exception {
        var id = insert(aPublishedWorkshop().withSeatsTaken(1).build());
        var first = get(id);

        jdbc.update("UPDATE workshops SET title = 'Changed behind its back' WHERE id = ?", id);
        var second = get(id);

        assertThat(second.get("title").asText())
                .as("slow-changing fields come from the 300s entry")
                .isEqualTo(first.get("title").asText());
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("the cached read gives the same answer as the database read")
    void cachedRead_equalsTheFreshRead() throws Exception {
        var id = insert(aPublishedWorkshop().withCapacity(40).withSeatsTaken(7).build());

        var fresh = get(id);
        var cached = get(id);

        assertThat(cached).isEqualTo(fresh);
    }

    @Test
    @DisplayName("seats expire on their own 3s clock while the detail stays cached")
    void seats_expireIndependentlyOfDetail() throws Exception {
        var id = insert(aPublishedWorkshop().withCapacity(40).withSeatsTaken(1).build());
        get(id);

        jdbc.update(
                "UPDATE workshops SET seats_taken = 9, title = 'Changed behind its back'"
                        + " WHERE id = ?",
                id);

        Awaitility.await()
                .atMost(Duration.ofSeconds(8))
                .untilAsserted(
                        () -> {
                            var body = get(id);
                            assertThat(body.get("seatsTaken").asInt()).isEqualTo(9);
                            assertThat(body.get("title").asText())
                                    .as("the 300s entry is untouched")
                                    .isNotEqualTo("Changed behind its back");
                        });
    }

    // ---------- refreshed when the source changes ----------

    @Test
    @DisplayName("an edit through the API is visible on the very next read")
    void edit_refreshesTheCache() throws Exception {
        var id = insert(aPublishedWorkshop().build());
        get(id);

        var patch = send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"title\":\"Renamed\"}");

        assertThat(patch.getStatusCode().value()).isEqualTo(200);
        assertThat(cached("detail", id)).as("dropped after the commit").isFalse();
        assertThat(get(id).get("title").asText()).isEqualTo("Renamed");
    }

    @Test
    @DisplayName("a reschedule is visible on the very next read")
    void reschedule_refreshesTheCache() throws Exception {
        var id = insert(aPublishedWorkshop().build());
        get(id);

        send(
                HttpMethod.PATCH,
                BASE + "/" + id,
                editor(),
                "{\"startTime\":\"2026-12-02T09:00:00Z\",\"endTime\":\"2026-12-02T11:00:00Z\"}");

        assertThat(get(id).get("startTime").asText()).startsWith("2026-12-02T09:00");
    }

    @Test
    @DisplayName("a cancel is visible on the very next read, status included")
    void cancel_refreshesTheCache() throws Exception {
        var id = insert(aPublishedWorkshop().build());
        assertThat(get(id).get("status").asText()).isEqualTo("PUBLISHED");

        send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);

        assertThat(get(id).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName(
            "a publish is visible to the public on the very next read, though a DRAFT was cached")
    void publish_refreshesTheCache() throws Exception {
        var id = insert(aDraftWorkshop().build());
        // A public read caches the DRAFT and then answers 404: the gate is applied after the cache.
        assertThat(send(HttpMethod.GET, BASE + "/" + id, null, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(cached("detail", id)).isTrue();

        var publish = send(HttpMethod.POST, BASE + "/" + id + "/publish", editor(), null);

        assertThat(publish.getStatusCode().value()).isEqualTo(200);
        assertThat(cached("detail", id)).as("dropped after the commit").isFalse();
        assertThat(get(id).get("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("a rejected edit leaves the cache as it was")
    void rejectedEdit_keepsTheCache() throws Exception {
        var id = insert(aPublishedWorkshop().withCapacity(50).withSeatsTaken(20).build());
        get(id);

        var patch = send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"capacity\":19}");

        assertThat(patch.getStatusCode().value()).isEqualTo(409);
        assertThat(cached("detail", id)).isTrue();
        assertThat(cached("seats", id)).isTrue();
    }

    @Test
    @DisplayName(
            "another module's seat change, told through Workshop's api, shows on the next read")
    void seatsChanged_throughTheApi() throws Exception {
        var id = insert(aPublishedWorkshop().withCapacity(40).withSeatsTaken(1).build());
        assertThat(get(id).get("seatsTaken").asInt()).isEqualTo(1);
        jdbc.update("UPDATE workshops SET seats_taken = 5 WHERE id = ?", id);

        seatInvalidation.seatsChanged(id);

        assertThat(cached("seats", id)).isFalse();
        assertThat(cached("detail", id)).as("slow-changing fields stay").isTrue();
        assertThat(get(id).get("seatsTaken").asInt()).isEqualTo(5);
    }

    // ---------- what the cache must never change ----------

    @Test
    @DisplayName("a DRAFT already in the cache is still 404 for an anonymous caller")
    void cachedDraft_staysHidden() {
        var id = insert(aDraftWorkshop().build());
        assertThat(send(HttpMethod.GET, BASE + "/" + id, null, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(cached("detail", id)).as("the first miss filled the cache").isTrue();

        var again = send(HttpMethod.GET, BASE + "/" + id, null, null);

        assertThat(again.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a manager reads the database and leaves the cache as it was (INV-03)")
    void managerRead_bypassesTheCache() throws Exception {
        var id = insert(aPublishedWorkshop().withTitle("Before").build());
        get(id);
        jdbc.update("UPDATE workshops SET title = 'Changed behind its back' WHERE id = ?", id);

        var asManager = send(HttpMethod.GET, BASE + "/" + id, editor(), null);

        assertThat(JSON.readTree(asManager.getBody()).get("title").asText())
                .as("not the cached 'Before'")
                .isEqualTo("Changed behind its back");
        assertThat(get(id).get("title").asText())
                .as("and the cache was neither read nor refilled")
                .isEqualTo("Before");

        var draft = insert(aDraftWorkshop().build());
        assertThat(send(HttpMethod.GET, BASE + "/" + draft, editor(), null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(cached("detail", draft)).isFalse();
    }

    @Test
    @DisplayName("an unknown workshop is 404 and leaves nothing in the cache")
    void unknown_isNotCached() {
        var id = UUID.randomUUID();

        var response = send(HttpMethod.GET, BASE + "/" + id, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(cached("detail", id)).isFalse();
        assertThat(cached("seats", id)).isFalse();
    }

    // ---------- lists ----------

    @Test
    @DisplayName("an identical search is served from the cache; a different one is not")
    void list_isCachedPerQuery() throws Exception {
        insert(aPublishedWorkshop().withTitle("Kubernetes basics").build());
        var first =
                JSON.readTree(send(HttpMethod.GET, BASE + "?q=kubernetes", null, null).getBody());
        assertThat(first.get("items").size()).isEqualTo(1);

        insert(aPublishedWorkshop().withTitle("Kubernetes advanced").build());
        var again =
                JSON.readTree(send(HttpMethod.GET, BASE + "?q=kubernetes", null, null).getBody());
        var different =
                JSON.readTree(
                        send(HttpMethod.GET, BASE + "?q=kubernetes&page=0&size=10", null, null)
                                .getBody());

        assertThat(again.get("items").size())
                .as("the 30s list entry predates the new workshop")
                .isEqualTo(1);
        assertThat(different.get("items").size()).as("another query is another key").isEqualTo(2);
    }

    @Test
    @DisplayName("a list entry lives at most 30s")
    void list_hasA30SecondTtl() {
        insert(aPublishedWorkshop().build());

        send(HttpMethod.GET, BASE, null, null);

        var ttls = redis.getKeys().getKeysStream().filter(k -> k.contains(":list:")).toList();
        assertThat(ttls).hasSize(1);
        assertThat(redis.getBucket(ttls.getFirst(), StringCodec.INSTANCE).remainTimeToLive())
                .isBetween(1L, 30_000L);
    }
}
