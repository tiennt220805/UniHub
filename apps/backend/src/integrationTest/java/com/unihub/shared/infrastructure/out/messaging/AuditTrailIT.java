package com.unihub.shared.infrastructure.out.messaging;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.stripe.net.Webhook;
import com.unihub.checkin.CheckInITSupport;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.AuditBroker;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.domain.model.Workshop;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-54 (Phase 6 checklist D71, 2.6; REQ-2.11.4): the four audited categories over real HTTP,
 * through the real {@code RabbitAuditTrail}, a real broker and the real consumer, into {@code
 * audit_events}: registration (free and paid), payment (charge, then the webhook), check-in sync,
 * and a workshop's reschedule and cancellation. Each row has the actor ({@code USER} with the
 * caller's id, or {@code SYSTEM} with its process), action, target and the time the operation
 * happened. An operation that is refused leaves no row.
 *
 * <p>The fifth category of architecture.md §5.5, a change of role or permission, has no use case
 * and so no producer (Phase 6 checklist D71). That a redelivered message does not write a second
 * row is {@code AuditRedeliveryIT}; that the message carries the request's correlation id is {@code
 * CorrelationPropagationIT} (3.6), since nothing puts it in the MDC on the HTTP side before the
 * filter of task 3.1.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditTrailIT extends CheckInITSupport {
    private static final String WEBHOOK_SECRET = "audit-it-webhook-secret";
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void auditProperties(DynamicPropertyRegistry registry) {
        AuditBroker.register(registry);
        registry.add("unihub.payment.stripe.webhook.secret", () -> WEBHOOK_SECRET);
    }

    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin amqpAdmin;

    @MockBean private PaymentGateway gateway;

    private RegistrationFixtures registrations;

    @BeforeEach
    void setUp() {
        AuditBroker.purgeAll(rabbit, amqpAdmin);
        registrations = new RegistrationFixtures(jdbc);
        reset(gateway);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
    }

    @AfterEach
    void cleanUp() {
        registrations.cleanUp();
    }

    // ---------- helpers ----------

    private record Row(
            String action,
            String actorType,
            UUID actorUserId,
            String targetType,
            UUID targetId,
            JsonNode metadata,
            Instant occurredAt) {}

    private List<Row> rows(String where, Object... args) {
        return jdbc
                .queryForList(
                        "SELECT action, actor_type, actor_user_id, target_type, target_id,"
                                + " metadata::text AS metadata, occurred_at FROM audit_events WHERE "
                                + where
                                + " ORDER BY recorded_at, action",
                        args)
                .stream()
                .map(
                        r -> {
                            try {
                                return new Row(
                                        (String) r.get("action"),
                                        (String) r.get("actor_type"),
                                        (UUID) r.get("actor_user_id"),
                                        (String) r.get("target_type"),
                                        (UUID) r.get("target_id"),
                                        JSON.readTree((String) r.get("metadata")),
                                        ((java.sql.Timestamp) r.get("occurred_at")).toInstant());
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .toList();
    }

    private List<Row> awaitRows(int count, String where, Object... args) {
        await().atMost(PATIENCE).until(() -> rows(where, args).size() >= count);
        return rows(where, args);
    }

    private WorkshopId saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return registrations.track(workshop.id());
    }

    private ResponseEntity<String> post(String path, String bearer, UUID key, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.add("Idempotency-Key", key.toString());
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> register(UserId student, WorkshopId workshop) {
        return post(
                "/api/v1/registrations",
                TestJwts.valid(student.value(), "REGISTRATION_CREATE"),
                UUID.randomUUID(),
                "{\"workshopId\":\"" + workshop.value() + "\"}");
    }

    private static UUID idOf(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(response.getBody()).get("id").asText());
    }

    private static void assertHappenedRecently(Row row, Instant after) {
        assertThat(row.occurredAt()).isBetween(after.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    // ---------- registration ----------

    @Test
    @DisplayName("a free registration is audited as REGISTRATION_CREATED by the student")
    void freeRegistration_isAudited() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = registrations.aStudent();
        var before = Instant.now();

        var registration = idOf(register(student, workshop));

        var row = awaitRows(1, "target_id = ?", registration).getFirst();
        assertThat(row.action()).isEqualTo("REGISTRATION_CREATED");
        assertThat(row.actorType()).isEqualTo("USER");
        assertThat(row.actorUserId()).isEqualTo(student.value());
        assertThat(row.targetType()).isEqualTo("REGISTRATION");
        assertHappenedRecently(row, before);
    }

    @Test
    @DisplayName("a paid registration (a held seat) is audited the same way")
    void paidRegistration_isAudited() throws Exception {
        var workshop = saved(aPaidWorkshop().withCapacity(5).build());
        var student = registrations.aStudent();

        var registration = idOf(register(student, workshop));

        var row = awaitRows(1, "target_id = ?", registration).getFirst();
        assertThat(row.action()).isEqualTo("REGISTRATION_CREATED");
        assertThat(row.actorUserId()).isEqualTo(student.value());
    }

    @Test
    @DisplayName(
            "a refused registration leaves no row: the full workshop is the proof, a later"
                    + " audited operation the barrier")
    void refusedRegistration_isNotAudited() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(1).build());
        var first = registrations.aStudent();
        var refused = registrations.aStudent();
        idOf(register(first, workshop));

        var response = register(refused, workshop);
        var barrier = registrations.aStudent();
        idOf(register(barrier, saved(aPublishedWorkshop().withCapacity(5).build())));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        awaitRows(1, "actor_user_id = ?", barrier.value());
        assertThat(rows("actor_user_id = ?", refused.value()))
                .as(
                        "the consumer is one thread and in order: the barrier's row is there, so the"
                                + " refused call's message would be too")
                .isEmpty();
    }

    // ---------- payment ----------

    @Test
    @DisplayName(
            "a charge is audited under the student, the webhook's resolution under the"
                    + " system process")
    void chargeAndWebhook_areAudited() throws Exception {
        var workshop = saved(aPaidWorkshop().withCapacity(5).build());
        var student = registrations.aStudent();
        var registration = idOf(register(student, workshop));

        var charged =
                post(
                        "/api/v1/payments/charge",
                        TestJwts.valid(student.value(), "REGISTRATION_CREATE"),
                        UUID.randomUUID(),
                        "{\"registrationId\":\"%s\",\"paymentMethodToken\":\"pm_card_visa\"}"
                                .formatted(registration));
        assertThat(charged.getStatusCode().is2xxSuccessful()).as(charged.getBody()).isTrue();
        var payment = JSON.readTree(charged.getBody());
        var paymentId = UUID.fromString(payment.get("id").asText());
        var gatewayId = payment.get("gatewayTransactionId").asText();

        var initiated = awaitRows(1, "target_id = ?", paymentId).getFirst();
        assertThat(initiated.action()).isEqualTo("PAYMENT_INITIATED");
        assertThat(initiated.actorType()).isEqualTo("USER");
        assertThat(initiated.actorUserId()).isEqualTo(student.value());
        assertThat(initiated.targetType()).isEqualTo("PAYMENT");
        assertThat(initiated.metadata().get("registrationId").asText())
                .isEqualTo(registration.toString());

        var event =
                "{\"id\":\"evt_%s\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":"
                                .formatted(UUID.randomUUID())
                        + "{\"id\":\"%s\",\"object\":\"payment_intent\"}}}".formatted(gatewayId);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(
                "Stripe-Signature",
                Webhook.Signature.generateSignatureHeader(
                        event, WEBHOOK_SECRET, Instant.now().getEpochSecond()));
        var webhook =
                http.exchange(
                        "/api/v1/webhooks/payment-gateway",
                        HttpMethod.POST,
                        new HttpEntity<>(event, headers),
                        String.class);
        assertThat(webhook.getStatusCode().value()).isEqualTo(200);

        var rows = awaitRows(2, "target_id = ?", paymentId);
        assertThat(rows)
                .extracting(Row::action)
                .containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCEEDED");
        var succeeded = rows.get(1);
        assertThat(succeeded.actorType()).isEqualTo("SYSTEM");
        assertThat(succeeded.actorUserId()).isNull();
        assertThat(succeeded.metadata().get("process").asText()).isEqualTo("payment-webhook");
    }

    // ---------- check-in ----------

    @Test
    @DisplayName(
            "a check-in sync is audited as CHECKIN_SCANS_SYNCED by the staff member, with the"
                    + " batch size")
    void checkInSync_isAudited() throws Exception {
        var ticket = fixtures.aConfirmedTicket(fixtures.aWorkshop());
        var scan = verified(ticket, T0, "dev-1");

        outcomes(sync(staff, scan));

        var row = awaitRows(1, "actor_user_id = ?", staff.value()).getFirst();
        assertThat(row.action()).isEqualTo("CHECKIN_SCANS_SYNCED");
        assertThat(row.actorType()).isEqualTo("USER");
        assertThat(row.targetType()).isEqualTo("CHECK_IN_BATCH");
        assertThat(row.metadata().get("scans").asInt()).isEqualTo(1);
        assertThat(row.metadata().get("new").asInt()).isEqualTo(1);
    }

    // ---------- workshop schedule ----------

    @Test
    @DisplayName("rescheduling and then cancelling a workshop are audited under the organizer")
    void rescheduleAndCancel_areAudited() {
        var workshop = saved(aPublishedWorkshop().build());
        var organizer = registrations.aStudent();
        var token = TestJwts.valid(organizer.value(), "WORKSHOP_EDIT");

        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var patch =
                http.exchange(
                        "/api/v1/workshops/" + workshop.value(),
                        HttpMethod.PATCH,
                        new HttpEntity<>(
                                "{\"startTime\":\"2026-12-02T09:00:00Z\","
                                        + "\"endTime\":\"2026-12-02T11:00:00Z\"}",
                                headers),
                        String.class);
        assertThat(patch.getStatusCode().is2xxSuccessful()).as(patch.getBody()).isTrue();
        var cancel =
                http.exchange(
                        "/api/v1/workshops/" + workshop.value() + "/cancel",
                        HttpMethod.POST,
                        new HttpEntity<>(null, headers),
                        String.class);
        assertThat(cancel.getStatusCode().is2xxSuccessful()).as(cancel.getBody()).isTrue();

        var rows = awaitRows(3, "target_id = ?", workshop.value());
        assertThat(rows)
                .extracting(Row::action)
                .containsExactlyInAnyOrder(
                        "WORKSHOP_UPDATED", "WORKSHOP_RESCHEDULED", "WORKSHOP_CANCELLED");
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.actorType()).isEqualTo("USER");
                            assertThat(row.actorUserId()).isEqualTo(organizer.value());
                            assertThat(row.targetType()).isEqualTo("WORKSHOP");
                        });
    }

    @Test
    @DisplayName("a cancel the domain refuses (already cancelled) is audited once, not twice")
    void refusedCancel_isNotAudited() {
        var workshop = saved(aPublishedWorkshop().build());
        var organizer = registrations.aStudent();
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(organizer.value(), "WORKSHOP_EDIT"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var path = "/api/v1/workshops/" + workshop.value() + "/cancel";

        http.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers), String.class);
        var again =
                http.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers), String.class);

        assertThat(again.getStatusCode().is2xxSuccessful()).isFalse();
        awaitRows(1, "target_id = ?", workshop.value());
        // the barrier: another audited operation by the same caller, after the refused one
        var barrier = saved(aPublishedWorkshop().build());
        http.exchange(
                "/api/v1/workshops/" + barrier.value() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                String.class);
        awaitRows(1, "target_id = ?", barrier.value());
        assertThat(rows("target_id = ?", workshop.value())).hasSize(1);
    }
}
