package com.unihub.workshop.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.domain.model.WorkshopBuilder;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * M-04 end to end: HTTP → security → controller → use case → JPA adapter → Postgres. Proves the
 * create / edit / reschedule / cancel flow, that COMPLETED and CANCELLED reject every change and
 * leave the stored row untouched, and the status codes of failures as problem+json. Rows are
 * committed, so each test deletes what it created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopLifecycleIT extends IntegrationTestSupport {

    private static final UUID USER = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/workshops";

    private static final String CREATE_BODY =
            """
            {"title":"Intro to Kubernetes","description":"Hands-on.","speakerName":"Ada Lovelace",
             "location":"Room 101","startTime":"2026-12-01T09:00:00Z",
             "endTime":"2026-12-01T11:00:00Z","capacity":50,"isPaid":false}""";

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

    @BeforeEach
    void allowPatch() {
        // The default HttpURLConnection-based factory cannot send PATCH.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void deleteCreatedRows() {
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
    }

    static Stream<WorkshopBuilder> terminalWorkshops() {
        return Stream.of(aCancelledWorkshop(), aCompletedWorkshop());
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, String body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String creator() {
        return TestJwts.valid(USER, "WORKSHOP_CREATE");
    }

    private static String editor() {
        return TestJwts.valid(USER, "WORKSHOP_EDIT");
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody());
    }

    private UUID createDraft() throws Exception {
        var response = send(HttpMethod.POST, BASE, creator(), CREATE_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var id = UUID.fromString(json(response).get("id").asText());
        created.add(id);
        return id;
    }

    private UUID insertDirectly(Workshop workshop) {
        created.add(workshop.id().value());
        WorkshopRows.insert(jdbc, workshop);
        return workshop.id().value();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM workshops WHERE id = ?", id);
    }

    private void assertProblem(ResponseEntity<String> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(
                        response.getHeaders()
                                .getContentType()
                                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
    }

    // ---------- create ----------

    @Test
    @DisplayName("POST creates a DRAFT workshop with no seats taken and persists the row")
    void create_persistsDraft() throws Exception {
        var response = send(HttpMethod.POST, BASE, creator(), CREATE_BODY);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var body = json(response);
        var id = UUID.fromString(body.get("id").asText());
        created.add(id);
        assertThat(body.get("status").asText()).isEqualTo("DRAFT");
        assertThat(body.get("seatsTaken").asInt()).isZero();
        assertThat(body.get("isPaid").asBoolean()).isFalse();
        assertThat(body.get("price").isNull()).isTrue();
        assertThat(body.get("hasBrochure").asBoolean()).isFalse();
        assertThat(body.get("version").asLong()).isZero();
        var row = row(id);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("title")).isEqualTo("Intro to Kubernetes");
        assertThat(row.get("capacity")).isEqualTo(50);
    }

    @Test
    @DisplayName("POST creates a paid workshop with its price")
    void create_paid() throws Exception {
        var body = CREATE_BODY.replace("\"isPaid\":false", "\"isPaid\":true,\"price\":150000.50");

        var response = send(HttpMethod.POST, BASE, creator(), body);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        created.add(UUID.fromString(json(response).get("id").asText()));
        assertThat(json(response).get("isPaid").asBoolean()).isTrue();
        assertThat(json(response).get("price").decimalValue()).isEqualByComparingTo("150000.50");
    }

    @Test
    @DisplayName("POST with endTime not after startTime is 400 problem+json and stores nothing")
    void create_invalid_400() {
        var before = jdbc.queryForObject("SELECT count(*) FROM workshops", Long.class);

        var response =
                send(
                        HttpMethod.POST,
                        BASE,
                        creator(),
                        CREATE_BODY.replace("2026-12-01T11:00:00Z", "2026-12-01T09:00:00Z"));

        assertProblem(response, 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workshops", Long.class))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("POST without a token is 401, with the wrong permission 403; nothing is stored")
    void create_authorization() {
        var before = jdbc.queryForObject("SELECT count(*) FROM workshops", Long.class);

        assertProblem(send(HttpMethod.POST, BASE, null, CREATE_BODY), 401);
        assertProblem(
                send(HttpMethod.POST, BASE, TestJwts.valid(USER, "WORKSHOP_EDIT"), CREATE_BODY),
                403);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM workshops", Long.class))
                .isEqualTo(before);
    }

    // ---------- edit ----------

    @Test
    @DisplayName("PATCH changes only the fields sent, clears an explicit null, bumps the version")
    void update_partial() throws Exception {
        var id = createDraft();

        var response =
                send(
                        HttpMethod.PATCH,
                        BASE + "/" + id,
                        editor(),
                        "{\"title\":\"Renamed\",\"description\":null,\"capacity\":60}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = json(response);
        assertThat(body.get("title").asText()).isEqualTo("Renamed");
        assertThat(body.get("description").isNull()).isTrue();
        assertThat(body.get("capacity").asInt()).isEqualTo(60);
        assertThat(body.get("location").asText()).isEqualTo("Room 101");
        assertThat(body.get("version").asLong()).isEqualTo(1);
        var row = row(id);
        assertThat(row.get("title")).isEqualTo("Renamed");
        assertThat(row.get("description")).isNull();
        assertThat(row.get("speaker_name")).isEqualTo("Ada Lovelace");
        assertThat(row.get("version")).isEqualTo(1);
    }

    @Test
    @DisplayName("PATCH of a capacity below the seats already taken is 409 and changes nothing")
    void update_capacityBelowSeatsTaken_409() throws Exception {
        var id = insertDirectly(aPublishedWorkshop().withCapacity(50).withSeatsTaken(20).build());

        var response = send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"capacity\":19}");

        assertProblem(response, 409);
        assertThat(row(id).get("capacity")).isEqualTo(50);
    }

    @Test
    @DisplayName("PATCH of a price on a free workshop is 409 (stateful rule) and changes nothing")
    void update_priceOnFree_409() throws Exception {
        var id = createDraft();

        var response = send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"price\":10}");

        assertProblem(response, 409);
        assertThat(row(id).get("price")).isNull();
    }

    // ---------- reschedule ----------

    @Test
    @DisplayName("PATCH with only startTime/endTime reschedules; the status stays")
    void reschedule() throws Exception {
        var id = insertDirectly(aPublishedWorkshop().build());

        var response =
                send(
                        HttpMethod.PATCH,
                        BASE + "/" + id,
                        editor(),
                        "{\"startTime\":\"2026-12-02T09:00:00Z\",\"endTime\":\"2026-12-02T11:00:00Z\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json(response).get("startTime").asText()).startsWith("2026-12-02T09:00");
        assertThat(json(response).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(row(id).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(id).get("start_time").toString()).contains("2026-12-02");
    }

    @Test
    @DisplayName(
            "moving only endTime to before the stored startTime is rejected and changes nothing")
    void reschedule_endBeforeStoredStart() throws Exception {
        var id = insertDirectly(aPublishedWorkshop().build());

        var response =
                send(
                        HttpMethod.PATCH,
                        BASE + "/" + id,
                        editor(),
                        "{\"endTime\":\"2026-11-30T09:00:00Z\"}");

        assertProblem(response, 409);
        assertThat(row(id).get("version")).isEqualTo(0);
    }

    // ---------- publish ----------

    @Test
    @DisplayName("POST /publish moves a DRAFT to PUBLISHED; publishing again is 409")
    void publish_thenPublishAgain() throws Exception {
        var id = createDraft();

        var first = send(HttpMethod.POST, BASE + "/" + id + "/publish", editor(), null);
        var second = send(HttpMethod.POST, BASE + "/" + id + "/publish", editor(), null);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(json(first).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(row(id).get("status")).isEqualTo("PUBLISHED");
        assertProblem(second, 409);
        assertThat(row(id).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("a DRAFT is 404 to the public until it is published, then 200 (no cache here)")
    void publish_makesTheWorkshopPublic() throws Exception {
        var id = createDraft();
        assertThat(send(HttpMethod.GET, BASE + "/" + id, null, null).getStatusCode().value())
                .isEqualTo(404);

        send(HttpMethod.POST, BASE + "/" + id + "/publish", editor(), null);

        assertThat(send(HttpMethod.GET, BASE + "/" + id, null, null).getStatusCode().value())
                .isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("terminalWorkshops")
    @DisplayName("a CANCELLED or COMPLETED workshop cannot be published: 409, row untouched")
    void publish_terminal_409(WorkshopBuilder builder) throws Exception {
        var workshop = builder.build();
        var id = insertDirectly(workshop);
        var before = row(id);

        var response = send(HttpMethod.POST, BASE + "/" + id + "/publish", editor(), null);

        assertProblem(response, 409);
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("publish: unknown id is 404, no token is 401, WORKSHOP_CREATE alone is 403")
    void publish_failures() throws Exception {
        var id = createDraft();

        assertProblem(
                send(HttpMethod.POST, BASE + "/" + UUID.randomUUID() + "/publish", editor(), null),
                404);
        assertProblem(send(HttpMethod.POST, BASE + "/" + id + "/publish", null, null), 401);
        assertProblem(send(HttpMethod.POST, BASE + "/" + id + "/publish", creator(), null), 403);
        assertThat(row(id).get("status")).isEqualTo("DRAFT");
    }

    // ---------- cancel ----------

    @Test
    @DisplayName("POST /cancel moves a workshop to CANCELLED; cancelling again is 409")
    void cancel_thenCancelAgain() throws Exception {
        var id = createDraft();

        var first = send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);
        var second = send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(json(first).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(row(id).get("status")).isEqualTo("CANCELLED");
        assertProblem(second, 409);
        assertThat(row(id).get("version")).isEqualTo(1);
    }

    @Test
    @DisplayName("a CANCELLED workshop rejects edit and reschedule with 409")
    void cancelled_rejectsEdits() throws Exception {
        var id = createDraft();
        send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);

        assertProblem(
                send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"title\":\"Nope\"}"), 409);
        assertProblem(
                send(
                        HttpMethod.PATCH,
                        BASE + "/" + id,
                        editor(),
                        "{\"startTime\":\"2026-12-02T09:00:00Z\",\"endTime\":\"2026-12-02T11:00:00Z\"}"),
                409);
        assertThat(row(id).get("title")).isEqualTo("Intro to Kubernetes");
    }

    @ParameterizedTest
    @MethodSource("terminalWorkshops")
    @DisplayName("a terminal workshop rejects cancel, edit and an empty edit; the row is untouched")
    void terminal_rejectsEverything(WorkshopBuilder builder) {
        var workshop = builder.build();
        var id = insertDirectly(workshop);
        var before = row(id);

        assertProblem(send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null), 409);
        assertProblem(
                send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"title\":\"Nope\"}"), 409);
        assertProblem(send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{}"), 409);

        assertThat(row(id)).isEqualTo(before);
    }

    // ---------- not found / authorization on the id endpoints ----------

    @Test
    @DisplayName("an unknown workshop id is 404 problem+json on PATCH and cancel")
    void unknownId_404() {
        var id = UUID.randomUUID();

        assertProblem(send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{}"), 404);
        assertProblem(send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null), 404);
    }

    @Test
    @DisplayName(
            "PATCH and cancel need WORKSHOP_EDIT: no token is 401, WORKSHOP_CREATE alone is 403")
    void idEndpoints_authorization() throws Exception {
        var id = createDraft();

        assertProblem(send(HttpMethod.PATCH, BASE + "/" + id, null, "{}"), 401);
        assertProblem(send(HttpMethod.PATCH, BASE + "/" + id, creator(), "{}"), 403);
        assertProblem(send(HttpMethod.POST, BASE + "/" + id + "/cancel", null, null), 401);
        assertProblem(send(HttpMethod.POST, BASE + "/" + id + "/cancel", creator(), null), 403);
        assertThat(row(id).get("status")).isEqualTo("DRAFT");
        assertThat(row(id).get("version")).isEqualTo(0);
    }
}
