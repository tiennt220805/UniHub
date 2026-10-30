package com.unihub.registration;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.api.TicketVerificationKey;
import com.unihub.registration.application.port.out.TicketSigner;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.domain.model.Workshop;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;

/**
 * M-42: a confirmed registration has a ticket (Phase 5 checklist D48, AC48.1-AC48.4, S-18), on the
 * paths a student takes, over real HTTP and with the real signer and database. Every ticket is
 * verified with the key {@link TicketVerificationKey} <em>publishes</em> (the one Check-in serves
 * to devices), never with a key the test knows from its own configuration: that is what shows the
 * published key and the signing key are one pair. The paid path delivers {@code charge.succeeded}
 * to the real listener by hand, as the other hand-off ITs do (the relay is off in tests, so the
 * test is the relay); the relay-driven flow is covered by {@code PaidRegistrationNotificationIT}.
 *
 * <p>"A thousand tickets" is a thousand calls of the real signer in this context, each verified,
 * plus the {@code UNIQUE} backstop on the column; the HTTP cases cover the two real paths one
 * registration at a time (a thousand registrations over HTTP would need a thousand seats and
 * students and would prove nothing the signer call does not).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketIssuanceIT extends HandOffITSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private TestRestTemplate http;
    @Autowired private TicketVerificationKey verificationKey;
    @Autowired private TicketSigner signer;

    @BeforeEach
    void useAFactoryThatCanRetryAStreamedPost() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    private WorkshopId saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private static HttpHeaders headers(UUID user, UUID key) {
        var headers = new HttpHeaders();
        if (user != null) {
            headers.setBearerAuth(TestJwts.valid(user, "REGISTRATION_CREATE"));
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.add("Idempotency-Key", key.toString());
        }
        return headers;
    }

    private ResponseEntity<String> register(UserId student, WorkshopId workshop, UUID key) {
        return http.exchange(
                "/api/v1/registrations",
                HttpMethod.POST,
                new HttpEntity<>(
                        "{\"workshopId\":\"" + workshop.value() + "\"}",
                        headers(student.value(), key)),
                String.class);
    }

    private ResponseEntity<String> get(String path, UUID user) {
        return http.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headers(user, null)), String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody());
    }

    @Test
    @DisplayName(
            "AC48.1, free: the 201 carries a ticket that the published key verifies, and it is the"
                    + " one stored")
    void freeRegistration_overHttp_hasAVerifiableTicket() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var before = Instant.now().minusSeconds(2);

        var response = register(student, workshop, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var body = json(response);
        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");
        var id = UUID.fromString(body.get("id").asText());
        var stored = TicketITSupport.storedTicket(jdbc, id);
        assertThat(body.get("ticketCode").asText()).isEqualTo(stored.code());
        assertThat(body.get("qrPayload").asText()).isEqualTo(stored.qrPayload());
        var claims =
                TicketITSupport.assertVerifies(stored, verificationKey.current(), workshop.value());
        assertThat(claims.keySet())
                .as("AC48.3: nothing but the three claims, on a real ticket")
                .containsExactlyInAnyOrder("ticket_code", "workshop_id", "iat");
        assertThat(((Number) claims.get("iat")).longValue())
                .isBetween(before.getEpochSecond(), Instant.now().plusSeconds(2).getEpochSecond());
        assertThat(stored.code()).matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    @DisplayName(
            "an Idempotency-Key replay returns the stored ticket: the same payload, one row, one"
                    + " signature")
    void replay_returnsTheStoredTicket_neverASecondOne() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var key = UUID.randomUUID();

        var first = register(student, workshop, key);
        var replay = register(student, workshop, key);

        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(json(replay).get("qrPayload").asText())
                .isEqualTo(json(first).get("qrPayload").asText());
        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "AC48.1, paid: RESERVED has no ticket; after the payment succeeds the registration is"
                    + " CONFIRMED with one the published key verifies")
    void paidRegistration_overHttp_getsItsTicketWhenPaymentSucceeds() throws Exception {
        var workshop = saved(aPaidWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var created = register(student, workshop, UUID.randomUUID());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        var registration = UUID.fromString(json(created).get("id").asText());
        assertThat(json(created).get("status").asText()).isEqualTo("RESERVED");
        assertThat(json(created).hasNonNull("qrPayload")).isFalse();
        assertThat(TicketITSupport.storedTicket(jdbc, registration).code()).isNull();

        var charged =
                http.exchange(
                        "/api/v1/payments/charge",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{\"registrationId\":\""
                                        + registration
                                        + "\",\"paymentMethodToken\":\"pm_card_visa\"}",
                                headers(student.value(), UUID.randomUUID())),
                        String.class);
        assertThat(charged.getStatusCode().value()).isEqualTo(201);
        var payment = UUID.fromString(json(charged).get("id").asText());
        publishChargeSucceeded(
                new Paid(student, workshop.value(), registration, payment, "pi_test"));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(registrationStatus(registration)).isEqualTo("CONFIRMED"));
        var stored = TicketITSupport.storedTicket(jdbc, registration);
        TicketITSupport.assertVerifies(stored, verificationKey.current(), workshop.value());
        assertThat(outboxRows(registration, "unihub.registration.ticket.confirmed")).isEqualTo(1);

        // the owner reads the very same ticket back (S-18's positive half)
        var read = get("/api/v1/registrations/" + registration, student.value());
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(json(read).get("qrPayload").asText()).isEqualTo(stored.qrPayload());
        assertThat(json(read).get("ticketCode").asText()).isEqualTo(stored.code());
    }

    @Test
    @DisplayName(
            "S-18: another student cannot read the ticket (403, nothing leaked), and a list never"
                    + " carries it")
    void anotherStudent_cannotReadTheTicket() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var owner = fixtures.aStudent();
        var stranger = fixtures.aStudent();
        var id = json(register(owner, workshop, UUID.randomUUID())).get("id").asText();
        var stored = TicketITSupport.storedTicket(jdbc, UUID.fromString(id));

        var denied = get("/api/v1/registrations/" + id, stranger.value());
        var list = get("/api/v1/registrations", owner.value());

        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(denied.getBody())
                .doesNotContain(stored.code())
                .doesNotContain(stored.qrPayload());
        assertThat(get("/api/v1/registrations/" + id, null).getStatusCode().value()).isEqualTo(401);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).doesNotContain(stored.code()).doesNotContain(stored.qrPayload());
    }

    @Test
    @DisplayName("AC48.2: a thousand tickets from the real signer are a thousand different ones")
    void thousandTickets_areDistinct_andEveryOneVerifies() throws Exception {
        var workshop = UUID.randomUUID();
        var codes = new HashSet<String>();
        var payloads = new HashSet<String>();

        for (int i = 0; i < 1_000; i++) {
            var ticket = signer.issue(new WorkshopId(workshop), Instant.now());
            codes.add(ticket.code());
            payloads.add(ticket.qrPayload());
            TicketITSupport.assertVerifies(
                    new TicketITSupport.StoredTicket(ticket.code(), ticket.qrPayload()),
                    verificationKey.current(),
                    workshop);
        }

        assertThat(codes).hasSize(1_000);
        assertThat(payloads).hasSize(1_000);
    }

    @Test
    @DisplayName("AC48.2 backstop: a second registration with an existing ticket_code is refused")
    void duplicateTicketCode_isRefusedByTheDatabase() {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var ticket = signer.issue(workshop, Instant.now());
        insertConfirmed(workshop, fixtures.aStudent(), ticket.code(), ticket.qrPayload());

        assertThatThrownBy(
                        () ->
                                insertConfirmed(
                                        workshop,
                                        fixtures.aStudent(),
                                        ticket.code(),
                                        "another.payload.signature"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("registrations_ticket_code_key");
    }

    private void insertConfirmed(WorkshopId workshop, UserId student, String code, String payload) {
        jdbc.update(
                "INSERT INTO registrations (id, workshop_id, user_id, ticket_type, status,"
                        + " ticket_code, qr_payload) VALUES (?, ?, ?, 'FREE', 'CONFIRMED', ?, ?)",
                UUID.randomUUID(),
                workshop.value(),
                student.value(),
                code,
                payload);
    }
}
