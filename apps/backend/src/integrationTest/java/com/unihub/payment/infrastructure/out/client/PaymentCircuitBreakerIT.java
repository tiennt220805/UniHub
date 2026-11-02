package com.unihub.payment.infrastructure.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stripe.net.Webhook;
import com.unihub.checkin.CheckInFixtures;
import com.unihub.checkin.CheckInITSupport;
import com.unihub.payment.application.command.ReconcilePaymentCommand;
import com.unihub.payment.application.port.in.ReconcilePaymentUseCase;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.PaymentId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
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
 * M-24, INV-05 (payment.md §2-§5, checklist D12, D17; resilience-testing.md §2.2 scenario 1): the
 * whole application, with the real Stripe adapter and the real circuit breaker pointed at a
 * WireMock stand-in for the gateway. A dead or slow gateway opens the one shared circuit; once open
 * no call reaches the gateway (the WireMock journal stays flat, which is stronger than reading the
 * breaker's state), neither from a charge nor from reconciliation; browsing, free registration and
 * the webhook carry on; and the circuit closes again by itself.
 *
 * <p>Small numbers keep it fast: a window and minimum of 4 calls, a 1 s open state, 2 probes; the
 * defaults are {@code PaymentBreakerPropertiesTest}'s. The gateway's HTTP timeouts (2 s) are longer
 * than the sync time limit (500 ms), so the time limiter is what cuts a slow call off. The Check-in
 * half of "browsing and check-in stay up" is {@link #checkInSync_andReporting_areUnaffected()}
 * (M-80, Phase 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentCircuitBreakerIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WEBHOOK_SECRET = "webhook-secret-for-tests";
    private static final String CREATE = "/v1/payment_intents";
    private static final String CONFIRM = "/v1/payment_intents/pi_ok/confirm";
    private static final int GATEWAY_PORT = freePort();

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
        registry.add("unihub.payment.stripe.webhook.secret", () -> WEBHOOK_SECRET);
        registry.add("unihub.payment.stripe.secret-key", () -> "sk_test_dummy");
        registry.add("unihub.payment.stripe.api-base", () -> "http://localhost:" + GATEWAY_PORT);
        registry.add("unihub.payment.stripe.connect-timeout", () -> "PT0.3S");
        registry.add("unihub.payment.stripe.read-timeout", () -> "PT2S");
        registry.add("unihub.payment.breaker.window-size", () -> "4");
        registry.add("unihub.payment.breaker.minimum-number-of-calls", () -> "4");
        registry.add("unihub.payment.breaker.wait-duration-in-open-state", () -> "PT1S");
        registry.add("unihub.payment.breaker.permitted-calls-in-half-open-state", () -> "2");
        registry.add("unihub.payment.breaker.sync-time-limit", () -> "PT0.5S");
        registry.add("unihub.payment.breaker.async-time-limit", () -> "PT1S");
        registry.add("unihub.payment.breaker.async-retry-attempts", () -> "2");
        registry.add("unihub.payment.breaker.async-retry-backoff", () -> "PT0.01S");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;
    @Autowired private CircuitBreaker paymentGatewayBreaker;
    @Autowired private ReconcilePaymentUseCase reconcile;

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
        if (!gateway.isRunning()) {
            gateway.start();
        }
        gateway.resetAll();
        paymentGatewayBreaker.reset();
    }

    @AfterEach
    void cleanUp() {
        paymentGatewayBreaker.reset();
        fixtures.cleanUp();
    }

    // ---------- the gateway stand-in ----------

    private void healthyGateway() {
        gateway.resetAll();
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("requires_confirmation"))));
        gateway.stubFor(
                post(urlEqualTo(CONFIRM))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("succeeded"))));
    }

    /** Every call is answered, but only after longer than the sync time limit. */
    private void slowGateway() {
        gateway.resetAll();
        gateway.stubFor(
                post(urlPathMatching("/v1/.*"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(1500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("requires_confirmation"))));
    }

    private static String intent(String status) {
        return "{\"id\":\"pi_ok\",\"object\":\"payment_intent\",\"status\":\"" + status + "\"}";
    }

    private int gatewayCalls() {
        return gateway.getAllServeEvents().size();
    }

    // ---------- HTTP helpers ----------

    private record Held(UserId student, UUID registration) {}

    private HttpHeaders headersFor(UUID user, UUID key) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(TestJwts.valid(user, "REGISTRATION_CREATE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", key.toString());
        return headers;
    }

    private ResponseEntity<String> register(UserId student, UUID workshop) {
        return http.exchange(
                "/api/v1/registrations",
                HttpMethod.POST,
                new HttpEntity<>(
                        "{\"workshopId\":\"" + workshop + "\"}",
                        headersFor(student.value(), UUID.randomUUID())),
                String.class);
    }

    private Held aHeldSeat() throws Exception {
        var workshop = aPaidWorkshop().withCapacity(20).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        var id = fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var response = register(student, id.value());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return new Held(
                student, UUID.fromString(JSON.readTree(response.getBody()).get("id").asText()));
    }

    private ResponseEntity<String> charge(Held held) {
        return http.exchange(
                "/api/v1/payments/charge",
                HttpMethod.POST,
                new HttpEntity<>(
                        "{\"registrationId\":\""
                                + held.registration()
                                + "\",\"paymentMethodToken\":\"pm_card_visa\"}",
                        headersFor(held.student().value(), UUID.randomUUID())),
                String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody());
    }

    private int paymentRows(UUID registration) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE registration_id = ?",
                Integer.class,
                registration);
    }

    /** Four charges the gateway fails: enough calls, all failing, to open the circuit. */
    private void failCharges(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            var response = charge(aHeldSeat());
            assertThat(response.getStatusCode().value()).isEqualTo(503);
        }
    }

    // ---------- scenario 1: dead gateway ----------

    @Test
    @DisplayName(
            "M-24: a dead gateway (connection refused) opens the circuit; charges then fail fast")
    void deadGateway_opensTheCircuit() throws Exception {
        gateway.stop(); // connection refused
        failCharges(4);
        assertThat(paymentGatewayBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        gateway.start(); // up again, but the circuit does not know yet
        gateway.resetAll();
        var held = aHeldSeat();
        var started = System.nanoTime();
        var refused = charge(held);

        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(refused.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("fails fast")
                .isLessThan(Duration.ofSeconds(1));
        assertThat(gatewayCalls()).as("no call reached the gateway").isZero();
        assertThat(paymentRows(held.registration()))
                .as("refused before the first transaction: no payments row (AC1.9)")
                .isZero();
        assertThat(fixtures.chargeKeys(held.student())).isZero();
    }

    @Test
    @DisplayName(
            "M-24: a slow gateway is cut off by the time limit, opens the circuit, and stays unreached")
    void slowGateway_opensTheCircuit_andTheJournalStaysFlat() throws Exception {
        slowGateway();
        var started = System.nanoTime();
        failCharges(4);
        // each charge is one call cut off at 500 ms: far less than the gateway's 1.5 s
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(10));
        assertThat(paymentGatewayBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        var callsWhenItOpened = gatewayCalls();
        assertThat(callsWhenItOpened).isEqualTo(4);

        // a charge, a reconciliation and another charge: none reaches the gateway
        var held = aHeldSeat();
        assertThat(charge(held).getStatusCode().value()).isEqualTo(503);
        var stuck = aStuckPayment();
        reconcile.reconcile(new ReconcilePaymentCommand(stuck));
        assertThat(charge(held).getStatusCode().value()).isEqualTo(503);

        assertThat(gatewayCalls())
                .as("the journal stayed flat for the open period")
                .isEqualTo(callsWhenItOpened);
        assertThat(paymentStatus(stuck))
                .as("reconciliation skipped the cycle and left the payment as it was")
                .isEqualTo("PENDING");
        assertThat(paymentRows(held.registration())).isZero();
    }

    @Test
    @DisplayName(
            "a confirm cut off by the time limit may have been sent: 201 PENDING, never cancelled")
    void stalledConfirm_staysPending_notCancelled() throws Exception {
        gateway.resetAll();
        gateway.stubFor(
                post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("requires_confirmation"))));
        gateway.stubFor(
                post(urlEqualTo(CONFIRM))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(1500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("succeeded"))));
        var held = aHeldSeat();

        var started = System.nanoTime();
        var response = charge(held);

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("cut off by the 500 ms limit, not by the gateway's 1.5 s answer (AC17.1)")
                .isLessThan(Duration.ofMillis(1400));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(json(response).get("status").asText()).isEqualTo("PENDING");
        assertThat(paymentRows(held.registration())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payments WHERE registration_id = ?",
                                String.class,
                                held.registration()))
                .isEqualTo("PENDING");
    }

    // ---------- fault isolation ----------

    @Test
    @DisplayName(
            "M-24, M-18: free registration, browsing and the webhook work with the circuit in every state")
    void freeRegistration_browsing_andTheWebhook_areUnaffected() throws Exception {
        healthyGateway();
        var states =
                new Runnable[] {
                    paymentGatewayBreaker::transitionToClosedState,
                    paymentGatewayBreaker::transitionToOpenState,
                    paymentGatewayBreaker::transitionToHalfOpenState
                };
        for (var enterState : states) {
            enterState.run();
            var state = paymentGatewayBreaker.getState();
            var workshop = aPublishedWorkshop().withCapacity(5).build();
            tx.executeWithoutResult(s -> workshops.save(workshop));
            var id = fixtures.track(workshop.id());
            var student = fixtures.aStudent();

            var registered = register(student, id.value());
            var browsed = http.getForEntity("/api/v1/workshops", String.class);
            var detail = http.getForEntity("/api/v1/workshops/" + id.value(), String.class);
            var body =
                    "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_unknown\"}}}";
            var webhook = webhook(body);

            assertThat(registered.getStatusCode().value())
                    .as("free registration, %s", state)
                    .isEqualTo(201);
            assertThat(json(registered).get("status").asText()).isEqualTo("CONFIRMED");
            assertThat(browsed.getStatusCode().value()).as("catalog, %s", state).isEqualTo(200);
            assertThat(detail.getStatusCode().value()).as("detail, %s", state).isEqualTo(200);
            assertThat(webhook.getStatusCode().value()).as("webhook, %s", state).isEqualTo(200);
            assertThat(paymentGatewayBreaker.getState())
                    .as("none of them touched the circuit")
                    .isEqualTo(state);
        }
        assertThat(gatewayCalls()).as("none of them called the gateway").isZero();
    }

    /**
     * M-80, the half of M-24 and M-27 that Phase 2 could not probe (Phase 5 checklist 5.3;
     * resilience-testing.md §2.1, §2.2): with the gateway really dead and the circuit really open,
     * check-in sync, the report and the attendance list answer {@code 2xx}, sync really stores its
     * scan, none of them reaches the gateway or moves the circuit.
     *
     * <p>The latency bound is absolute, like the other bounds of this class: resilience-testing.md
     * §2.1 leaves the multiplier over a fault-free baseline open, because the corpus gives it no
     * source, and none is invented here.
     */
    @Test
    @DisplayName(
            "M-80, M-24: with the gateway dead and the circuit open, check-in sync and the reports carry on")
    void checkInSync_andReporting_areUnaffected() throws Exception {
        var checkIn = new CheckInFixtures(jdbc, workshops, tx);
        try {
            var venue = checkIn.aWorkshop();
            var staff = checkIn.aStaffMember();
            var tickets =
                    java.util.List.of(
                            checkIn.aConfirmedTicket(venue),
                            checkIn.aConfirmedTicket(venue),
                            checkIn.aConfirmedTicket(venue));
            gateway.stop(); // connection refused
            failCharges(4);
            assertThat(paymentGatewayBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            gateway.start(); // the circuit does not know yet; the journal can now be read
            gateway.resetAll();
            var syncToken = TestJwts.valid(staff.value(), "CHECKIN_SCAN");
            var reportToken = TestJwts.valid(staff.value(), "REPORT_VIEW");
            var states =
                    new Runnable[] {
                        () -> {}, // the state the failures left: OPEN
                        paymentGatewayBreaker::transitionToHalfOpenState,
                        paymentGatewayBreaker::transitionToClosedState
                    };

            for (var round = 0; round < states.length; round++) {
                states[round].run();
                var state = paymentGatewayBreaker.getState();
                var ticket = tickets.get(round);
                var scan =
                        new CheckInITSupport.Scan(
                                UUID.randomUUID(),
                                "VERIFIED",
                                "\"" + ticket.code() + "\"",
                                venue.value(),
                                Instant.now(),
                                "dev-1");

                var synced =
                        timed(
                                () ->
                                        bearerPost(
                                                "/api/v1/checkin/sync",
                                                syncToken,
                                                "{\"scans\":[" + scan.json() + "]}"));
                var report =
                        timed(
                                () ->
                                        bearerGet(
                                                "/api/v1/workshops/" + venue.value() + "/report",
                                                reportToken));
                var list =
                        timed(
                                () ->
                                        bearerGet(
                                                "/api/v1/workshops/"
                                                        + venue.value()
                                                        + "/attendances",
                                                reportToken));

                assertThat(synced.response().getStatusCode().is2xxSuccessful())
                        .as("sync, %s", state)
                        .isTrue();
                assertThat(report.response().getStatusCode().is2xxSuccessful())
                        .as("report, %s", state)
                        .isTrue();
                assertThat(list.response().getStatusCode().is2xxSuccessful())
                        .as("attendances, %s", state)
                        .isTrue();
                for (var probe : java.util.List.of(synced, report, list)) {
                    assertThat(probe.took())
                            .as("latency, %s", state)
                            .isLessThan(Duration.ofSeconds(2));
                }
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT outcome FROM check_in_scans WHERE id = ?",
                                        String.class,
                                        scan.id()))
                        .as("sync really stored its scan, %s", state)
                        .isEqualTo("ACCEPTED");
                var figures = JSON.readTree(report.response().getBody());
                assertThat(figures.get("confirmedCount").asInt()).isEqualTo(3);
                assertThat(figures.get("checkedInCount").asInt()).isEqualTo(round + 1);
                assertThat(
                                JSON.readTree(list.response().getBody())
                                        .get("page")
                                        .get("totalElements")
                                        .asInt())
                        .isEqualTo(round + 1);
                assertThat(paymentGatewayBreaker.getState())
                        .as("none of them touched the circuit")
                        .isEqualTo(state);
            }
            assertThat(gatewayCalls()).as("none of them called the gateway").isZero();
        } finally {
            checkIn.cleanUp();
        }
    }

    private record Timed(ResponseEntity<String> response, Duration took) {}

    private Timed timed(java.util.function.Supplier<ResponseEntity<String>> request) {
        var started = System.nanoTime();
        var response = request.get();
        return new Timed(response, Duration.ofNanos(System.nanoTime() - started));
    }

    private ResponseEntity<String> bearerGet(String path, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> bearerPost(String path, String token, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> webhook(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            headers.add(
                    "Stripe-Signature",
                    Webhook.Signature.generateSignatureHeader(
                            body, WEBHOOK_SECRET, Instant.now().getEpochSecond()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return http.exchange(
                "/api/v1/webhooks/payment-gateway",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    // ---------- recovery ----------

    @Test
    @DisplayName(
            "M-24: once the gateway is back the circuit probes by itself and a charge succeeds")
    void recovers_withNoManualStep() throws Exception {
        slowGateway();
        failCharges(4);
        assertThat(paymentGatewayBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        healthyGateway();
        var held = aHeldSeat();
        // within the open state the circuit still refuses
        assertThat(charge(held).getStatusCode().value()).isEqualTo(503);
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> {
                            var response = charge(held);
                            assertThat(response.getStatusCode().value()).isEqualTo(201);
                            assertThat(json(response).get("status").asText())
                                    .isEqualTo("SUCCEEDED");
                        });

        // create and confirm were the two probes the half-open state lets through
        assertThat(paymentGatewayBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------- helpers for the reconciliation probe ----------

    private PaymentId aStuckPayment() throws Exception {
        var held = aHeldSeat();
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payments (id, registration_id, amount, currency, gateway,"
                        + " gateway_transaction_id, status, initiated_at) VALUES (?, ?, 100000,"
                        + " 'VND', 'STRIPE', ?, 'PENDING', now() - interval '20 minutes')",
                id,
                held.registration(),
                "pi_stuck_" + id);
        return PaymentId.of(id);
    }

    private String paymentStatus(PaymentId id) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, id.value());
    }
}
