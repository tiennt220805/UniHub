package com.unihub.workshop.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.shared.application.port.in.RelayPendingEventsUseCase;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.infrastructure.out.persistence.WorkshopRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
 * Phase 2 §1.4: what cancelling and rescheduling a workshop leave behind besides the new state — an
 * outbox row for Notification (INV-10) and an audit line — over HTTP, on real PostgreSQL and
 * RabbitMQ. Both exist exactly when the change committed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopEventsIT extends IntegrationTestSupport {

    private static final UUID USER = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/workshops";
    private static final String NOTIFICATION_QUEUE = "unihub.notification.queue";

    private static final String CREATE_BODY =
            """
            {"title":"Intro to Kubernetes","description":"Hands-on.","speakerName":"Ada Lovelace",
             "location":"Room 101","startTime":"2026-12-01T09:00:00Z",
             "endTime":"2026-12-01T11:00:00Z","capacity":50,"isPaid":false}""";

    private static final String RESCHEDULE_BODY =
            "{\"startTime\":\"2026-12-02T09:00:00Z\",\"endTime\":\"2026-12-02T11:00:00Z\"}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RelayPendingEventsUseCase relay;
    @Autowired private RabbitTemplate template;
    @Autowired private RabbitAdmin admin;

    private final List<UUID> created = new ArrayList<>();
    private final ListAppender<ILoggingEvent> auditLines = new ListAppender<>();
    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("unihub.audit");

    @BeforeEach
    void setUp() {
        // The default HttpURLConnection-based factory cannot send PATCH.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        jdbc.update("DELETE FROM event_publications");
        auditLines.start();
        auditLogger.addAppender(auditLines);
    }

    @AfterEach
    void cleanUp() {
        auditLogger.detachAppender(auditLines);
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id));
        jdbc.update("DELETE FROM event_publications");
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String editor() {
        return TestJwts.valid(USER, "WORKSHOP_EDIT");
    }

    private UUID createDraft() throws Exception {
        var response =
                send(HttpMethod.POST, BASE, TestJwts.valid(USER, "WORKSHOP_CREATE"), CREATE_BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var id = UUID.fromString(JSON.readTree(response.getBody()).get("id").asText());
        created.add(id);
        return id;
    }

    private UUID insertDirectly(Workshop workshop) {
        created.add(workshop.id().value());
        WorkshopRows.insert(jdbc, workshop);
        return workshop.id().value();
    }

    private List<Map<String, Object>> outbox() {
        return jdbc.queryForList(
                "SELECT id, aggregate_type, aggregate_id, event_type, routing_key,"
                        + " payload::text AS payload, status FROM event_publications");
    }

    private List<JsonNode> auditActions() throws Exception {
        var parsed = new ArrayList<JsonNode>();
        for (var line : auditLines.list) {
            parsed.add(JSON.readTree(line.getFormattedMessage()));
        }
        return parsed;
    }

    // ---------- the outbox ----------

    @Test
    @DisplayName(
            "cancelling leaves exactly one PENDING WorkshopCancelled row addressed to the workshop")
    void cancel_leavesOneCancelledRow() throws Exception {
        var id = createDraft();

        var response = send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(outbox()).hasSize(1);
        var row = outbox().getFirst();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("routing_key")).isEqualTo("unihub.workshop.event.cancelled");
        assertThat(row.get("aggregate_type")).isEqualTo("WORKSHOP");
        assertThat(row.get("aggregate_id")).isEqualTo(id);
        assertThat(row.get("event_type")).isEqualTo("WorkshopCancelledIntegrationEvent");
        var payload = JSON.readTree((String) row.get("payload"));
        assertThat(payload.get("workshopId").asText()).isEqualTo(id.toString());
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.has("occurredAt")).isTrue();
    }

    @Test
    @DisplayName(
            "moving the times leaves one PENDING WorkshopRescheduled row with old and new times")
    void reschedule_leavesOneRescheduledRow() throws Exception {
        var id = insertDirectly(aPublishedWorkshop().build());

        var response = send(HttpMethod.PATCH, BASE + "/" + id, editor(), RESCHEDULE_BODY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(outbox()).hasSize(1);
        var row = outbox().getFirst();
        assertThat(row.get("routing_key")).isEqualTo("unihub.workshop.event.rescheduled");
        assertThat(row.get("aggregate_id")).isEqualTo(id);
        var payload = JSON.readTree((String) row.get("payload"));
        assertThat(payload.get("newStartTime").asText()).startsWith("2026-12-02T09:00");
        assertThat(payload.get("newEndTime").asText()).startsWith("2026-12-02T11:00");
        assertThat(payload.get("previousStartTime").asText()).doesNotStartWith("2026-12-02");
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an edit that does not move the times leaves no outbox row")
    void edit_withoutNewTimes_leavesNoRow() throws Exception {
        var id = insertDirectly(aPublishedWorkshop().build());

        var response = send(HttpMethod.PATCH, BASE + "/" + id, editor(), "{\"title\":\"Renamed\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(outbox()).isEmpty();
    }

    @Test
    @DisplayName("creating a workshop publishes nothing")
    void create_leavesNoRow() throws Exception {
        createDraft();

        assertThat(outbox()).isEmpty();
    }

    @Test
    @DisplayName("a rejected cancel leaves no outbox row")
    void rejectedCancel_leavesNoRow() {
        var id = insertDirectly(aCancelledWorkshop().build());

        var response = send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(outbox()).isEmpty();
    }

    @Test
    @DisplayName("a reschedule that fails a rule leaves no outbox row")
    void rejectedReschedule_leavesNoRow() {
        var id = insertDirectly(aPublishedWorkshop().build());

        var response =
                send(
                        HttpMethod.PATCH,
                        BASE + "/" + id,
                        editor(),
                        "{\"endTime\":\"2026-11-30T09:00:00Z\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(outbox()).isEmpty();
    }

    @Test
    @DisplayName("the cancelled event reaches unihub.notification.queue through the relay")
    void cancelledEvent_reachesTheNotificationQueue() throws Exception {
        admin.initialize();
        admin.purgeQueue(NOTIFICATION_QUEUE);
        var id = createDraft();
        send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);
        var rowId = outbox().getFirst().get("id");

        int published = relay.relay(200);

        assertThat(published).isEqualTo(1);
        var message = template.receive(NOTIFICATION_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(rowId.toString());
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo("unihub.workshop.event.cancelled");
        assertThat(JSON.readTree(message.getBody()).get("workshopId").asText())
                .isEqualTo(id.toString());
        admin.purgeQueue(NOTIFICATION_QUEUE);
    }

    // ---------- the audit trail ----------

    @Test
    @DisplayName("creating is audited as WORKSHOP_CREATED by the calling user")
    void create_isAudited() throws Exception {
        var id = createDraft();

        var lines = auditActions();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().get("action").asText()).isEqualTo("WORKSHOP_CREATED");
        assertThat(lines.getFirst().get("targetType").asText()).isEqualTo("WORKSHOP");
        assertThat(lines.getFirst().get("targetId").asText()).isEqualTo(id.toString());
        assertThat(lines.getFirst().get("actorType").asText()).isEqualTo("USER");
        assertThat(lines.getFirst().get("actorUserId").asText()).isEqualTo(USER.toString());
    }

    @Test
    @DisplayName("a reschedule is audited as WORKSHOP_UPDATED and WORKSHOP_RESCHEDULED")
    void reschedule_isAudited() throws Exception {
        var id = insertDirectly(aPublishedWorkshop().build());

        send(HttpMethod.PATCH, BASE + "/" + id, editor(), RESCHEDULE_BODY);

        assertThat(auditActions())
                .extracting(line -> line.get("action").asText())
                .containsExactly("WORKSHOP_UPDATED", "WORKSHOP_RESCHEDULED");
    }

    @Test
    @DisplayName("an operation that was rejected is not audited")
    void rejectedOperation_isNotAudited() {
        var id = insertDirectly(aCancelledWorkshop().build());

        send(HttpMethod.POST, BASE + "/" + id + "/cancel", editor(), null);

        assertThat(auditLines.list).isEmpty();
    }
}
