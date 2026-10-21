package com.unihub.payment.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.payment.application.port.out.ConfirmOutcome;
import com.unihub.payment.application.port.out.GatewayNotReachedException;
import com.unihub.payment.application.port.out.PaymentGateway;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code chargePayment} end to end (M-19, M-20, T-CHARGE, T-PAY): HTTP → security → controller →
 * charge → Postgres, with only the gateway doubled. A processed charge is 201 with the real status
 * and replays byte for byte; the refusals are 409 problems with their own {@code type}; ownership
 * is 403 and a missing registration 404, both recording nothing; a charge that could not have moved
 * money is 503 and leaves the key free. Rows are committed, so each test cleans up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentControllerIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/api/v1/payments/charge";

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

    @MockBean private PaymentGateway gateway;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        // The default HttpURLConnection factory cannot retry an authenticated streamed POST.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fixtures = new RegistrationFixtures(jdbc);
        reset(gateway);
        when(gateway.create(any(), any(), any(), any()))
                .thenAnswer(call -> "pi_" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    /** A student holding a RESERVED seat, taken through the real registration endpoint. */
    private record Held(UserId student, UUID registration) {}

    private Held aHeldSeat() throws Exception {
        var workshop = aPaidWorkshop().withCapacity(5).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var id = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var headers = headersFor(student.value(), UUID.randomUUID());
        var response =
                http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>("{\"workshopId\":\"" + id.value() + "\"}", headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return new Held(student, UUID.fromString(json(response).get("id").asText()));
    }

    private static HttpHeaders headersFor(UUID user, UUID key) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(user, "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", key.toString());
        return headers;
    }

    private ResponseEntity<String> charge(UUID asUser, UUID registration, UUID key, String token) {
        var body =
                "{\"registrationId\":\""
                        + registration
                        + "\",\"paymentMethodToken\":\""
                        + token
                        + "\"}";
        return http.exchange(
                PATH,
                HttpMethod.POST,
                new HttpEntity<>(body, headersFor(asUser, key)),
                String.class);
    }

    private ResponseEntity<String> charge(Held held, UUID key) {
        return charge(held.student().value(), held.registration(), key, "pm_card_visa");
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
        if (type != null) {
            assertThat(json(response).get("type").asText()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("a successful charge is 201 SUCCEEDED; the same key replays the identical body")
    void success_is201_andReplaysIdentically() throws Exception {
        var held = aHeldSeat();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());

        var first = charge(held, key);
        var replay = charge(held, key);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        var body = json(first);
        assertThat(body.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(body.get("registrationId").asText()).isEqualTo(held.registration().toString());
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(body.get("currency").asText()).isEqualTo("VND");
        assertThat(body.get("gateway").asText()).isEqualTo("STRIPE");
        assertThat(body.get("gatewayTransactionId").asText()).startsWith("pi_");
        assertThat(body.hasNonNull("resolvedAt")).isTrue();
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        verify(gateway, times(1)).confirm(any(), any());
        assertThat(fixtures.paymentOutboxRows(held.registration())).isEqualTo(1);
    }

    @Test
    @DisplayName("a declined card is 201 FAILED, and a new key may try again")
    void decline_is201Failed_thenNewKeyWorks() throws Exception {
        var held = aHeldSeat();
        when(gateway.confirm(any(), any()))
                .thenReturn(new ConfirmOutcome.Declined("generic_decline"))
                .thenReturn(new ConfirmOutcome.Succeeded());

        var declined = charge(held, UUID.randomUUID());
        var retried = charge(held, UUID.randomUUID());

        assertThat(declined.getStatusCode().value()).isEqualTo(201);
        assertThat(json(declined).get("status").asText()).isEqualTo("FAILED");
        assertThat(retried.getStatusCode().value()).isEqualTo(201);
        assertThat(json(retried).get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("a confirm that may have been sent is 201 PENDING with no resolvedAt")
    void unknownOutcome_is201Pending() throws Exception {
        var held = aHeldSeat();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());

        var response = charge(held, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(json(response).get("status").asText()).isEqualTo("PENDING");
        assertThat(json(response).get("resolvedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("a second key while a payment is live is 409 payment-in-progress")
    void secondKey_isPaymentInProgress() throws Exception {
        var held = aHeldSeat();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Processing());
        charge(held, UUID.randomUUID());

        var second = charge(held, UUID.randomUUID());

        assertProblem(second, 409, "urn:unihub:problem:payment-in-progress");
        verify(gateway, times(1)).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "a registration whose hold has ended is 409 registration-not-reserved, no gateway call")
    void expiredHold_isNotReserved() throws Exception {
        var held = aHeldSeat();
        fixtures.expireTheHold(held.registration());

        var response = charge(held, UUID.randomUUID());

        assertProblem(response, 409, "urn:unihub:problem:registration-not-reserved");
        verify(gateway, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("another student's registration is 403 and records nothing")
    void otherUsersRegistration_is403() throws Exception {
        var held = aHeldSeat();
        var stranger = fixtures.aStudent();

        var response = charge(stranger.value(), held.registration(), UUID.randomUUID(), "pm_x");

        assertProblem(response, 403, null);
        assertThat(fixtures.chargeKeys(stranger)).isZero();
        assertThat(fixtures.paymentStatuses(held.registration())).isEmpty();
        verify(gateway, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown registration is 404 and records nothing")
    void unknownRegistration_is404() throws Exception {
        var held = aHeldSeat();

        var response = charge(held.student().value(), UUID.randomUUID(), UUID.randomUUID(), "pm_x");

        assertProblem(response, 404, null);
        assertThat(fixtures.chargeKeys(held.student())).isZero();
    }

    @Test
    @DisplayName("the same key with another body is 422 idempotency-key-reused")
    void sameKeyOtherBody_is422() throws Exception {
        var held = aHeldSeat();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        charge(held, key);

        var other = charge(held.student().value(), held.registration(), key, "pm_card_mastercard");

        assertProblem(other, 422, "urn:unihub:problem:idempotency-key-reused");
    }

    @Test
    @DisplayName("nothing charged is 503 with Retry-After; the same key then runs for real")
    void gatewayUnavailable_is503_andTheKeyIsFree() throws Exception {
        var held = aHeldSeat();
        var key = UUID.randomUUID();
        when(gateway.confirm(any(), any()))
                .thenThrow(new GatewayNotReachedException("refused", null))
                .thenReturn(new ConfirmOutcome.Succeeded());

        var unavailable = charge(held, key);
        var retry = charge(held, key);

        assertProblem(unavailable, 503, null);
        assertThat(unavailable.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(json(retry).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(fixtures.paymentStatuses(held.registration()))
                .containsExactly("CANCELLED", "SUCCEEDED");
    }

    @Test
    @DisplayName("an actor named in the body is ignored: the payer is the token's subject")
    void actorInBody_isIgnored() throws Exception {
        var held = aHeldSeat();
        var stranger = fixtures.aStudent();
        when(gateway.confirm(any(), any())).thenReturn(new ConfirmOutcome.Succeeded());
        var body =
                "{\"registrationId\":\""
                        + held.registration()
                        + "\",\"paymentMethodToken\":\"pm_card_visa\",\"userId\":\""
                        + held.student().value()
                        + "\"}";

        var response =
                http.exchange(
                        PATH,
                        HttpMethod.POST,
                        new HttpEntity<>(body, headersFor(stranger.value(), UUID.randomUUID())),
                        String.class);

        assertProblem(response, 403, null);
    }

    @Test
    @DisplayName("a missing or malformed Idempotency-Key, or a blank token, is 400")
    void badRequests_are400() throws Exception {
        var held = aHeldSeat();
        var headers = headersFor(held.student().value(), UUID.randomUUID());
        headers.remove("Idempotency-Key");
        var body =
                "{\"registrationId\":\"" + held.registration() + "\",\"paymentMethodToken\":\"x\"}";

        var noKey =
                http.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        var blank = charge(held.student().value(), held.registration(), UUID.randomUUID(), "");

        assertThat(noKey.getStatusCode().value()).isEqualTo(400);
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(fixtures.chargeKeys(held.student())).isZero();
    }
}
