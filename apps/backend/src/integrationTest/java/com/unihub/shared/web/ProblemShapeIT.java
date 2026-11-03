package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-17: every non-2xx response is the one RFC 7807 shape of {@code
 * packages/api-contract/common/error.yaml}, whichever layer produced it — the domain advice, Spring
 * MVC's own request errors, Spring Security's filters, or an unexpected exception. contract-diff
 * proves the <em>declared</em> shapes match the contract (contract-testing.md §1); this proves the
 * bodies actually sent do. Success responses are checked to be plain JSON, never problem+json.
 * Every response, failure or success, carries {@code X-Correlation-Id}, and every problem body the
 * same value as {@code correlationId} (M-17, Phase 6 checklist 3.5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(ProblemShapeIT.FailingProbe.class)
class ProblemShapeIT extends IntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/workshops";
    private static final String FAILING_PATH = "/api/v1/test-only/unexpected-failure";
    private static final String IDEMPOTENT_PATH = "/api/v1/test-only/idempotent";
    private static final String KEY_IN_USE_PATH = "/api/v1/test-only/idempotency-key-in-use";
    private static final String KEY_REUSED_PATH = "/api/v1/test-only/idempotency-key-reused";
    private static final String INTERNAL_MESSAGE =
            "internal detail that must never leave the server";

    private static final String CREATE_BODY =
            """
            {"title":"Shape check","description":"d","speakerName":"s","location":"Room 1",
             "startTime":"2026-12-01T09:00:00Z","endTime":"2026-12-01T11:00:00Z",
             "capacity":10,"isPaid":false}""";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    /** Stands in for a bug: a handler that throws something no advice was written for. */
    @TestConfiguration
    static class FailingProbe {
        @RestController
        static class Failing {
            @GetMapping(FAILING_PATH)
            String fail() {
                throw new IllegalStateException(INTERNAL_MESSAGE);
            }

            /** What every idempotent write declares: the contract's required uuid header. */
            @PostMapping(path = IDEMPOTENT_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
            String idempotent(@RequestHeader("Idempotency-Key") UUID key) {
                return "\"" + key + "\"";
            }

            @PostMapping(KEY_IN_USE_PATH)
            String keyInUse() {
                throw new IdempotencyKeyInUseException(IdempotencyScope.REGISTRATION_CREATE);
            }

            @PostMapping(KEY_REUSED_PATH)
            String keyReused() {
                throw new IdempotencyKeyReusedException(IdempotencyScope.PAYMENT_CHARGE);
            }
        }
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void allowPatch() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void deleteCreatedRows() {
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
    }

    private static String editor() {
        return TestJwts.valid(UUID.randomUUID(), "WORKSHOP_CREATE", "WORKSHOP_EDIT");
    }

    private ResponseEntity<String> send(
            HttpMethod method, String path, String token, MediaType contentType, String body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, String json) {
        return send(method, path, token, json == null ? null : MediaType.APPLICATION_JSON, json);
    }

    private UUID createWorkshop() throws Exception {
        var response = send(HttpMethod.POST, BASE, editor(), CREATE_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var id = UUID.fromString(JSON.readTree(response.getBody()).get("id").asText());
        created.add(id);
        return id;
    }

    /**
     * error.yaml: {@code application/problem+json}; {@code type}, {@code title}, {@code status}
     * required, {@code status} equal to the HTTP status; {@code detail} and {@code instance}
     * strings when present, {@code instance} being the request path. Nothing internal (stack trace,
     * exception class) leaks.
     */
    private static JsonNode assertProblem(ResponseEntity<String> response, int status, String path)
            throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        var contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("Content-Type of a %d", status)
                .isTrue();

        var body = JSON.readTree(response.getBody());
        assertThat(body.isObject()).isTrue();
        assertThat(body.path("type").isTextual()).as("type").isTrue();
        assertThat(body.path("title").isTextual()).as("title").isTrue();
        assertThat(body.path("title").asText()).isNotBlank();
        assertThat(body.path("status").isInt()).as("status is an integer").isTrue();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        if (body.has("detail")) {
            assertThat(body.get("detail").isTextual()).as("detail").isTrue();
        }
        if (body.has("instance")) {
            assertThat(body.get("instance").asText()).isEqualTo(path);
        }
        assertThat(body.has("trace")).as("no stack trace").isFalse();
        assertThat(body.has("exception")).as("no exception class").isFalse();
        // M-17 (Phase 6 checklist 3.5): correlationId is on every problem body, and it is the very
        // value of the response header, whichever layer wrote the body.
        var header = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(header).as("X-Correlation-Id on a %d", status).matches("[0-9a-f]{32}");
        assertThat(body.path("correlationId").asText())
                .as("correlationId on a %d", status)
                .isEqualTo(header);
        return body;
    }

    private static void assertJson(ResponseEntity<String> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        var contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isFalse();
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .as("X-Correlation-Id on a success")
                .matches("[0-9a-f]{32}");
    }

    // ---------- success ----------

    @Test
    @DisplayName("success responses of every implemented operation are application/json")
    void success_isPlainJson() throws Exception {
        assertJson(send(HttpMethod.GET, BASE, null, null), 200);
        var id = createWorkshop();
        assertJson(send(HttpMethod.GET, BASE + "/" + id, editor(), null), 200);
        assertJson(
                send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"title\":\"Renamed\"}"), 200);
        assertJson(send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null), 200);
    }

    // ---------- 400: Spring MVC request errors and domain validation ----------

    @Test
    @DisplayName("400 from a query parameter out of range")
    void badRequest_queryParameter() throws Exception {
        assertProblem(send(HttpMethod.GET, BASE + "?size=1000", null, null), 400, BASE);
    }

    @Test
    @DisplayName("400 from a malformed path id")
    void badRequest_malformedPathId() throws Exception {
        assertProblem(
                send(HttpMethod.GET, BASE + "/not-a-uuid", null, null), 400, BASE + "/not-a-uuid");
    }

    @Test
    @DisplayName("400 from a body that is not JSON")
    void badRequest_unparsableBody() throws Exception {
        assertProblem(send(HttpMethod.POST, BASE, editor(), "{not json"), 400, BASE);
    }

    @Test
    @DisplayName("400 from a body that fails bean validation")
    void badRequest_invalidBody() throws Exception {
        assertProblem(send(HttpMethod.POST, BASE, editor(), "{\"title\":\"\"}"), 400, BASE);
    }

    @Test
    @DisplayName("400 from a domain rule (dateFrom after dateTo)")
    void badRequest_domainRule() throws Exception {
        assertProblem(
                send(HttpMethod.GET, BASE + "?dateFrom=2026-12-02&dateTo=2026-12-01", null, null),
                400,
                BASE);
    }

    // ---------- 401 / 403: Spring Security filters ----------

    @Test
    @DisplayName("401 without a token, and with a token that does not verify")
    void unauthorized() throws Exception {
        assertProblem(send(HttpMethod.POST, BASE, null, CREATE_BODY), 401, BASE);
        assertProblem(send(HttpMethod.POST, BASE, "not.a.jwt", CREATE_BODY), 401, BASE);
    }

    @Test
    @DisplayName("403 with a valid token that lacks the permission")
    void forbidden() throws Exception {
        assertProblem(
                send(HttpMethod.POST, BASE, TestJwts.valid(UUID.randomUUID()), CREATE_BODY),
                403,
                BASE);
    }

    // ---------- 404 / 405 / 409 / 415 ----------

    @Test
    @DisplayName("404 for an unknown workshop (domain)")
    void notFound_domain() throws Exception {
        var path = BASE + "/" + UUID.randomUUID();
        assertProblem(send(HttpMethod.GET, path, null, null), 404, path);
    }

    @Test
    @DisplayName("404 for a path no controller maps")
    void notFound_unmappedPath() throws Exception {
        var path = "/api/v1/no-such-resource";
        assertProblem(send(HttpMethod.GET, path, editor(), null), 404, path);
    }

    @Test
    @DisplayName("405 for a method the path does not support")
    void methodNotAllowed() throws Exception {
        assertProblem(send(HttpMethod.DELETE, BASE, editor(), null), 405, BASE);
    }

    @Test
    @DisplayName("409 when the current state forbids the change (cancel twice)")
    void conflict() throws Exception {
        var path = BASE + "/" + createWorkshop() + "/cancel";
        assertJson(send(HttpMethod.POST, path, editor(), null), 200);

        assertProblem(send(HttpMethod.POST, path, editor(), null), 409, path);
    }

    @Test
    @DisplayName("415 for a body in a media type the operation does not accept")
    void unsupportedMediaType() throws Exception {
        assertProblem(
                send(HttpMethod.POST, BASE, editor(), MediaType.TEXT_PLAIN, CREATE_BODY),
                415,
                BASE);
    }

    // ---------- Idempotency-Key header and its two problems ----------

    private ResponseEntity<String> sendWithKey(String path, String key) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(editor());
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers), String.class);
    }

    @Test
    @DisplayName("a well-formed Idempotency-Key is accepted, in either letter case")
    void idempotencyKey_wellFormed() {
        var key = UUID.randomUUID();

        assertJson(sendWithKey(IDEMPOTENT_PATH, key.toString()), 200);
        assertJson(sendWithKey(IDEMPOTENT_PATH, key.toString().toUpperCase()), 200);
    }

    @Test
    @DisplayName("400 when the Idempotency-Key header is missing")
    void idempotencyKey_missing() throws Exception {
        assertProblem(sendWithKey(IDEMPOTENT_PATH, null), 400, IDEMPOTENT_PATH);
    }

    @Test
    @DisplayName("400 when the Idempotency-Key header is not a uuid")
    void idempotencyKey_malformed() throws Exception {
        assertProblem(sendWithKey(IDEMPOTENT_PATH, "not-a-uuid"), 400, IDEMPOTENT_PATH);
        assertProblem(sendWithKey(IDEMPOTENT_PATH, ""), 400, IDEMPOTENT_PATH);
    }

    @Test
    @DisplayName("409 when the same key is still being processed")
    void idempotencyKey_inUse() throws Exception {
        assertProblem(
                sendWithKey(KEY_IN_USE_PATH, UUID.randomUUID().toString()), 409, KEY_IN_USE_PATH);
    }

    @Test
    @DisplayName("422 when the same key arrives with a different body")
    void idempotencyKey_reused() throws Exception {
        assertProblem(
                sendWithKey(KEY_REUSED_PATH, UUID.randomUUID().toString()), 422, KEY_REUSED_PATH);
    }

    // ---------- 500 ----------

    @Test
    @DisplayName("500 from an unexpected exception is problem+json and leaks no internal detail")
    void unexpectedFailure() throws Exception {
        var body =
                assertProblem(
                        send(HttpMethod.GET, FAILING_PATH, editor(), null), 500, FAILING_PATH);

        assertThat(body.toString()).doesNotContain(INTERNAL_MESSAGE);
        assertThat(body.toString()).doesNotContain("IllegalStateException");
    }
}
