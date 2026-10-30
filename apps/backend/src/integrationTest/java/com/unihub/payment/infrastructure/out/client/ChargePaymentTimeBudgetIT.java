package com.unihub.payment.infrastructure.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AC17.1 (checklist D17, payment.md §3): the time budget of a synchronous charge, measured with the
 * documented defaults and no shortcut. A charge makes two gateway calls, {@code create} then {@code
 * confirm}; each is cut off at its own 3 s, so a stalled call frees its thread within 3 s and the
 * worst request is two calls plus a short transaction, about 6.5 s. {@code PaymentCircuitBreakerIT}
 * proves the circuit with small numbers; this class is the one that keeps the real ones honest.
 *
 * <p>The numbers are real seconds, so the bounds are generous in the slow direction (a loaded CI
 * machine) and exact in the direction that matters (a call is never allowed to run past its limit).
 * The breaker keeps its defaults too (10 calls minimum), so these few calls never open it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChargePaymentTimeBudgetIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "/v1/payment_intents";
    private static final String CONFIRM = "/v1/payment_intents/pi_slow/confirm";
    private static final int GATEWAY_PORT = freePort();
    private static final Duration LIMIT = Duration.ofSeconds(3);

    private static WireMockServer gateway;

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.payment.stripe.secret-key", () -> "sk_test_dummy");
        registry.add("unihub.payment.stripe.api-base", () -> "http://localhost:" + GATEWAY_PORT);
        // Everything else is left at its default on purpose: the 3 s time limit per call, the 3 s
        // HTTP timeouts and the circuit's window and minimum number of calls.
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;
    @Autowired private CircuitBreaker paymentGatewayBreaker;

    private RegistrationFixtures fixtures;

    @BeforeAll
    static void startGateway() {
        gateway = new WireMockServer(WireMockConfiguration.options().port(GATEWAY_PORT));
        gateway.start();
    }

    @AfterAll
    static void stopGateway() {
        gateway.stop();
    }

    @BeforeEach
    void setUp() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fixtures = new RegistrationFixtures(jdbc);
        gateway.resetAll();
        paymentGatewayBreaker.reset();
    }

    @AfterEach
    void cleanUp() {
        paymentGatewayBreaker.reset();
        fixtures.cleanUp();
    }

    private void stubCreate(int delayMillis) {
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(delayMillis)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("requires_confirmation"))));
    }

    private void stubConfirm(int delayMillis) {
        gateway.stubFor(
                post(urlEqualTo(CONFIRM))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(delayMillis)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("succeeded"))));
    }

    private static String intent(String status) {
        return "{\"id\":\"pi_slow\",\"object\":\"payment_intent\",\"status\":\"" + status + "\"}";
    }

    private record Held(UserId student, UUID registration) {}

    private Held aHeldSeat() throws Exception {
        var workshop = aPaidWorkshop().withCapacity(20).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var id = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var response =
                http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{\"workshopId\":\"" + id.value() + "\"}",
                                headers(student.value())),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return new Held(
                student, UUID.fromString(JSON.readTree(response.getBody()).get("id").asText()));
    }

    private HttpHeaders headers(UUID user) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(user, "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    /** The charge, and how long the request took from the student's side. */
    private record Timed(ResponseEntity<String> response, Duration took) {}

    private Timed timedCharge(Held held) {
        var started = System.nanoTime();
        var response =
                http.exchange(
                        "/api/v1/payments/charge",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{\"registrationId\":\""
                                        + held.registration()
                                        + "\",\"paymentMethodToken\":\"pm_card_visa\"}",
                                headers(held.student().value())),
                        String.class);
        return new Timed(response, Duration.ofNanos(System.nanoTime() - started));
    }

    private String paymentStatus(UUID registration) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE registration_id = ?",
                String.class,
                registration);
    }

    @Test
    @DisplayName(
            "a stalled create is cut off at 3 s: the student gets the 503 then, not after the gateway")
    void stalledCreate_isCutAtTheLimit() throws Exception {
        stubCreate(10_000);
        var held = aHeldSeat();

        var charge = timedCharge(held);

        assertThat(charge.response().getStatusCode().value()).isEqualTo(503);
        assertThat(charge.took()).isGreaterThanOrEqualTo(LIMIT.minusMillis(100));
        assertThat(charge.took()).isLessThan(LIMIT.plusSeconds(2));
        assertThat(paymentStatus(held.registration())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName(
            "a stalled confirm is cut off at 3 s too, and the payment stays PENDING (it may have been sent)")
    void stalledConfirm_isCutAtTheLimit() throws Exception {
        stubCreate(0);
        stubConfirm(10_000);
        var held = aHeldSeat();

        var charge = timedCharge(held);

        assertThat(charge.response().getStatusCode().value()).isEqualTo(201);
        JsonNode body = JSON.readTree(charge.response().getBody());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(charge.took()).isGreaterThanOrEqualTo(LIMIT.minusMillis(100));
        assertThat(charge.took()).isLessThan(LIMIT.plusSeconds(2));
    }

    @Test
    @DisplayName(
            "the worst request, a slow create then a stalled confirm, is two calls and within the budget")
    void worstCase_isAboutSixAndAHalfSeconds() throws Exception {
        // Well inside its 3 s limit (1 s to spare, for a loaded runner and a cold first call), so
        // the charge goes on to confirm. At 2.5 s a slow CI machine cut the create off and the
        // charge answered 503 instead of 201.
        stubCreate(2_000);
        stubConfirm(10_000);
        var held = aHeldSeat();

        var charge = timedCharge(held);

        assertThat(charge.response().getStatusCode().value())
                .as("status after %s, body %s", charge.took(), charge.response().getBody())
                .isEqualTo(201);
        // 2 s for create, 3 s until confirm is cut off, plus the two short transactions; the
        // documented ceiling (payment.md §3) is two full 3 s calls and a transaction, about 6.5 s.
        assertThat(charge.took())
                .as("measured %s", charge.took())
                .isGreaterThanOrEqualTo(Duration.ofMillis(4_900));
        assertThat(charge.took())
                .as("measured %s", charge.took())
                .isLessThan(Duration.ofMillis(7_000));
        assertThat(gateway.getAllServeEvents()).hasSize(2); // neither call was repeated
    }
}
