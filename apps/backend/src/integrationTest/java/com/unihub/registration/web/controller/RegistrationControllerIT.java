package com.unihub.registration.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-18 end to end: HTTP → security → controller → use case → seat claim → Postgres. A free workshop
 * confirms at once, takes exactly one seat in the same transaction, never creates a payment, and
 * leaves {@code ticket_code}/{@code qr_payload} {@code NULL} (no ticket issuance before Phase 5).
 * The refusals are 409 problems with their own {@code type}, a missing workshop is 404, and a
 * refusal writes nothing. Rows are committed, so each test cleans up after itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationControllerIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/api/v1/registrations";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RegistrationFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private WorkshopId saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id());
    }

    private ResponseEntity<String> register(UserId student, WorkshopId workshop) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(student.value(), "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var body = "{\"workshopId\":\"" + workshop.value() + "\"}";
        return http.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody());
    }

    private void assertProblem(ResponseEntity<String> response, int status, String type)
            throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(
                        response.getHeaders()
                                .getContentType()
                                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(json(response).get("type").asText()).isEqualTo(type);
    }

    private int payments() {
        return jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class);
    }

    @Test
    @DisplayName("a free workshop: 201 CONFIRMED, one seat taken, no payment, no ticket yet")
    void free_isConfirmedAtOnce() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        var paymentsBefore = payments();

        var response = register(student, workshop);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var body = json(response);
        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.get("ticketType").asText()).isEqualTo("FREE");
        assertThat(body.get("workshopId").asText()).isEqualTo(workshop.value().toString());
        assertThat(body.get("userId").asText()).isEqualTo(student.value().toString());
        assertThat(body.hasNonNull("holdExpiresAt")).isFalse();
        assertThat(body.hasNonNull("ticketCode")).isFalse();
        assertThat(body.hasNonNull("createdAt")).isTrue();

        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        var row =
                jdbc.queryForMap(
                        "SELECT status, ticket_type, hold_expires_at, ticket_code, qr_payload "
                                + "FROM registrations WHERE id = ?",
                        UUID.fromString(body.get("id").asText()));
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("ticket_type")).isEqualTo("FREE");
        assertThat(row.get("hold_expires_at")).isNull();
        assertThat(row.get("ticket_code")).isNull();
        assertThat(row.get("qr_payload")).isNull();
        // the gateway is never involved: no payment row appears
        assertThat(payments()).isEqualTo(paymentsBefore);
    }

    @Test
    @DisplayName("a paid workshop: 201 RESERVED with a hold, PAID, seat taken, still no payment")
    void paid_isReserved() throws Exception {
        var workshop = saved(aPaidWorkshop().withCapacity(5).build());

        var response = register(fixtures.aStudent(), workshop);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var body = json(response);
        assertThat(body.get("status").asText()).isEqualTo("RESERVED");
        assertThat(body.get("ticketType").asText()).isEqualTo("PAID");
        assertThat(body.get("holdExpiresAt").asText()).isNotBlank();
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("registering twice is 409 already-registered and takes no second seat")
    void twice_isAlreadyRegistered() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(5).build());
        var student = fixtures.aStudent();
        register(student, workshop);

        var second = register(student, workshop);

        assertProblem(second, 409, "urn:unihub:problem:already-registered");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("a full workshop is 409 sold-out and writes nothing")
    void full_isSoldOut() throws Exception {
        var workshop = saved(aPublishedWorkshop().withCapacity(1).build());
        register(fixtures.aStudent(), workshop);

        var response = register(fixtures.aStudent(), workshop);

        assertProblem(response, 409, "urn:unihub:problem:sold-out");
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(fixtures.registrationRows(workshop)).isEqualTo(1);
    }

    @Test
    @DisplayName("a cancelled or draft workshop is 409 registration-not-open and writes nothing")
    void notPublished_isNotOpen() throws Exception {
        for (var workshop :
                new Workshop[] {aCancelledWorkshop().build(), aDraftWorkshop().build()}) {
            var id = saved(workshop);

            var response = register(fixtures.aStudent(), id);

            assertProblem(response, 409, "urn:unihub:problem:registration-not-open");
            assertThat(fixtures.seatsTaken(id)).isZero();
            assertThat(fixtures.registrationRows(id)).isZero();
        }
    }

    @Test
    @DisplayName("an unknown workshop is 404 problem+json, nothing written")
    void unknownWorkshop_is404() throws Exception {
        var response = register(fixtures.aStudent(), WorkshopId.newId());

        assertProblem(response, 404, "about:blank");
    }
}
