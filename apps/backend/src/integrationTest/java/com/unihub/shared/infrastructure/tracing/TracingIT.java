package com.unihub.shared.infrastructure.tracing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.brochure.BrochureHttpSupport;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-56 (Phase 6 checklist D73-D75, 5.5; REQ-3.1.2): the spans of the real application, caught in
 * memory — HTTP, JDBC, AMQP, the gateway, the object store and the AI service — and the shape of
 * the traces they form. A request and the consumer of the message it caused are <em>two</em> traces
 * (the relay publishes from a trace of its own, observability.md §1) joined by one {@code
 * correlationId}; a span never carries what the guardrail of §2 keeps out of Tempo: a bind value, a
 * bearer token, a key, a signed URL, the text of an exception.
 *
 * <p>Sampling is 1.0, the relay and the listeners run, Stripe is the real adapter against WireMock,
 * the brochure flow uses the real MinIO and a WireMock AI service. The exporter is synchronous, so
 * a span is there when it has ended.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(TracingIT.InMemorySpans.class)
class TracingIT extends BrochureHttpSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    private static final String STRIPE_KEY = "sk_test_tracing_it_key";
    private static final String CARD_TOKEN = "pm_card_tracing_it";
    private static final String CREATE = "/v1/payment_intents";
    private static final String CONFIRM = "/v1/payment_intents/pi_traced/confirm";

    private static WireMockServer stripe;

    @TestConfiguration
    static class InMemorySpans {
        /**
         * An exporter and not a processor: Boot wraps it in the same batch processor the OTLP
         * exporter gets, and {@code TracingConfig} wraps it in the scrubber, so what is read here
         * is what would leave the process.
         */
        @Bean
        SpanExporter inMemorySpans() {
            return SPANS;
        }
    }

    @BeforeAll
    static void startStripe() {
        stripe = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stripe.start();
    }

    @AfterAll
    static void stopStripe() {
        stripe.stop();
    }

    @DynamicPropertySource
    static void tracingProperties(DynamicPropertyRegistry registry) {
        registry.add("management.tracing.sampling.probability", () -> "1.0");
        registry.add("unihub.jobs.outbox-relay.enabled", () -> "true");
        registry.add("unihub.jobs.outbox-relay.interval", () -> "PT0.2S");
        registry.add("unihub.payment.stripe.secret-key", () -> STRIPE_KEY);
        registry.add("unihub.payment.stripe.api-base", () -> "http://localhost:" + stripe.port());
        registry.add("unihub.payment.stripe.connect-timeout", () -> "PT0.5S");
        registry.add("unihub.payment.stripe.read-timeout", () -> "PT2S");
    }

    @Autowired private SdkTracerProvider tracerProvider;

    private RegistrationFixtures fixtures;

    /** The batch processor sends every few seconds; a test reads after asking it to send now. */
    private List<SpanData> spans() {
        tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
        return SPANS.getFinishedSpanItems();
    }

    @BeforeEach
    void freshSpans() {
        jdbc.update("DELETE FROM event_publications");
        fixtures = new RegistrationFixtures(jdbc);
        stripe.resetAll();
        SPANS.reset();
    }

    @AfterEach
    void cleanUp() {
        await().atMost(Duration.ofSeconds(20))
                .until(
                        () ->
                                jdbc.queryForObject(
                                                "SELECT count(*) FROM event_publications"
                                                        + " WHERE status = 'PENDING'",
                                                Integer.class)
                                        == 0);
        // The confirmation notification of a paid registration points at its student, and it is
        // written by a consumer a moment after the registration is confirmed: delete until none
        // is left to appear.
        await().atMost(Duration.ofSeconds(30))
                .ignoreExceptions()
                .untilAsserted(
                        () -> {
                            jdbc.update(
                                    "DELETE FROM notifications WHERE user_id IN"
                                            + " (SELECT id FROM users WHERE email LIKE '%@test.local')");
                            fixtures.cleanUp();
                        });
    }

    // ---------- the paid registration: HTTP, JDBC, the gateway, AMQP ----------

    @Test
    @DisplayName(
            "M-56: a charge is one trace (HTTP, JDBC, the gateway), its consumer another, one correlationId")
    void paidRegistration_spansAndTwoTraces() {
        stripeAnswers("succeeded");
        var workshop = aPaidWorkshop().withCapacity(20).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var email =
                jdbc.queryForObject(
                        "SELECT email FROM users WHERE id = ?", String.class, student.value());
        var token = TestJwts.valid(student.value(), "REGISTRATION_CREATE");

        var registered =
                send(
                        "/api/v1/registrations",
                        token,
                        "{\"workshopId\":\"" + workshop.id().value() + "\"}");
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var registration = id(registered);
        var charged =
                send(
                        "/api/v1/payments/charge",
                        token,
                        "{\"registrationId\":\""
                                + registration
                                + "\",\"paymentMethodToken\":\""
                                + CARD_TOKEN
                                + "\"}");
        assertThat(charged.getStatusCode().is2xxSuccessful()).as(charged.getBody()).isTrue();
        var requestTrace = charged.getHeaders().getFirst("X-Correlation-Id");
        assertThat(requestTrace).hasSize(32);

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT status FROM registrations WHERE id = ?",
                                                        String.class,
                                                        UUID.fromString(registration)))
                                        .isEqualTo("CONFIRMED"));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> consumerSpans("unihub.payment-confirmation.queue").size() >= 1);

        var all = spans();
        var inRequest = inTrace(all, requestTrace);
        var summary = summary(all);

        assertThat(inRequest.stream().filter(s -> s.getKind() == SpanKind.SERVER))
                .as("the HTTP span of the charge request\n%s", summary)
                .isNotEmpty();
        assertThat(named(inRequest, "unihub.stripe.request"))
                .as(
                        "the gateway call, inside the request's trace despite the virtual thread\n%s",
                        summary)
                .isNotEmpty()
                .allSatisfy(
                        s -> {
                            assertThat(s.getAttributes().asMap().toString())
                                    .contains("outcome=success");
                        });
        assertThat(inRequest.stream().filter(TracingIT::isJdbc))
                .as("the statements of the request\n%s", summary)
                .isNotEmpty();

        // The audit entry is published from an executor thread: the request's trace is carried
        // across explicitly (RabbitAuditTrail), because the observation current at record() is the
        // JDBC layer's, not the request's. Its consumer continues the trace of the publish.
        assertThat(inRequest.stream().filter(sp -> sp.getKind() == SpanKind.PRODUCER))
                .as("the audit publish of the charge, inside the request's trace\n%s", summary)
                .isNotEmpty();
        var auditProducers =
                all.stream()
                        .filter(sp -> sp.getKind() == SpanKind.PRODUCER)
                        .filter(sp -> sp.getName().contains("audit.created"))
                        .toList();
        assertThat(auditProducers).as("the audit publishes\n%s", summary).isNotEmpty();
        assertThat(consumerSpans("unihub.audit.queue").stream().map(SpanData::getTraceId).toList())
                .as("an audit consumer continues the trace of its publish")
                .containsAnyElementsOf(auditProducers.stream().map(SpanData::getTraceId).toList());

        var published =
                all.stream()
                        .filter(s -> s.getKind() == SpanKind.PRODUCER)
                        .filter(s -> !s.getTraceId().equals(requestTrace))
                        .toList();
        var consumed = consumerSpans("unihub.payment-confirmation.queue");
        assertThat(published).as("the relay's publishes\n%s", summary).isNotEmpty();
        assertThat(consumed).as("the consumer\n%s", summary).isNotEmpty();
        var consumerTrace = consumed.get(0).getTraceId();
        assertThat(consumerTrace)
                .as("the consumer is not in the request's trace (the relay publishes from its own)")
                .isNotEqualTo(requestTrace);
        assertThat(published.stream().map(SpanData::getTraceId))
                .as("the consumer continues the trace of the publish")
                .contains(consumerTrace);
        assertThat(
                        jdbc.queryForList(
                                "SELECT correlation_id FROM event_publications WHERE routing_key = ?"
                                        + " AND payload ->> 'registrationId' = ?",
                                String.class,
                                com.unihub.shared.infrastructure.out.messaging.Topology
                                        .CHARGE_SUCCEEDED,
                                registration))
                .as("the message carries the request's id, so the two traces are joined")
                .containsOnly(requestTrace);

        assertNothingSensitive(
                all, token, STRIPE_KEY, CARD_TOKEN, email, student.value().toString());
    }

    // ---------- a failing gateway ----------

    @Test
    @DisplayName(
            "M-56: a failing gateway call is a span with a failure outcome and no URL or message")
    void failingGateway_isAnOutcomeNotAMessage() {
        stripe.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"message\":\"boom for a@b.vn\"}}")));
        var workshop = aPaidWorkshop().withCapacity(20).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        fixtures.track(workshop.id());
        var student = fixtures.aStudent();
        var token = TestJwts.valid(student.value(), "REGISTRATION_CREATE");
        var registered =
                send(
                        "/api/v1/registrations",
                        token,
                        "{\"workshopId\":\"" + workshop.id().value() + "\"}");
        var charged =
                send(
                        "/api/v1/payments/charge",
                        token,
                        "{\"registrationId\":\""
                                + id(registered)
                                + "\",\"paymentMethodToken\":\""
                                + CARD_TOKEN
                                + "\"}");
        assertThat(charged.getStatusCode().is5xxServerError()).isTrue();

        var all = spans();
        assertThat(named(all, "unihub.stripe.request"))
                .as(summary(all))
                .isNotEmpty()
                .allSatisfy(
                        s ->
                                assertThat(s.getAttributes().asMap().toString())
                                        .contains("outcome=failure"));
        assertNothingSensitive(
                all, token, STRIPE_KEY, CARD_TOKEN, "boom for", "localhost:" + stripe.port());
    }

    // ---------- the brochure: the object store and the AI service ----------

    @Test
    @DisplayName(
            "M-56: the confirmation reads the store inside the request; the consumer calls the store and the AI in its own trace")
    void brochure_storeAndAiSpans() throws Exception {
        var workshop = aWorkshop();
        aiSummarizes("Một bản tóm tắt.");
        var issued = issue(workshop, organizer());
        assertThat(put(uploadUrl(issued), A_PDF)).isEqualTo(200);
        var confirmed = confirm(workshop, organizer());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        var requestTrace = confirmed.getHeaders().getFirst("X-Correlation-Id");

        await().atMost(Duration.ofSeconds(30))
                .until(() -> !named(spans(), "unihub.ai.summarize").isEmpty());

        var all = spans();
        var summary = summary(all);
        assertThat(named(inTrace(all, requestTrace), "unihub.s3.request"))
                .as("the store is read by the confirmation request\n%s", summary)
                .isNotEmpty();
        var ai = named(all, "unihub.ai.summarize");
        assertThat(ai.get(0).getTraceId()).as(summary).isNotEqualTo(requestTrace);
        assertThat(ai.get(0).getAttributes().asMap().toString()).contains("outcome=success");
        assertThat(named(inTrace(all, ai.get(0).getTraceId()), "unihub.s3.request"))
                .as("the consumer fetches the object in the same trace as its AI call\n%s", summary)
                .isNotEmpty();
        assertNothingSensitive(
                all, organizer(), "test-key-not-a-secret", "X-Amz-Signature", "X-Amz-Credential");
    }

    // ---------- a statement that fails ----------

    @Test
    @DisplayName("M-56: a statement the database refuses does not put the row's values in a span")
    void refusedStatement_keepsValuesOutOfTheSpan() {
        var email = "dup-" + UUID.randomUUID() + "@tracing.test";
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, ?)",
                UUID.randomUUID(),
                email,
                "Tracing One");
        SPANS.reset();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () ->
                            jdbc.update(
                                    "INSERT INTO users (id, email, full_name) VALUES (?, ?, ?)",
                                    UUID.randomUUID(),
                                    email,
                                    "Tracing Two"));
        } finally {
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }

        var all = spans();
        assertThat(all.stream().filter(TracingIT::isJdbc))
                .as("the statements are spans at all\n%s", summary(all))
                .isNotEmpty();
        assertNothingSensitive(all, email, "Tracing One", "Tracing Two");
    }

    // ---------- the master is wrapped, the router is not ----------

    @Test
    @DisplayName(
            "M-56: the pools are wrapped by the JDBC observation and the router in front of them is not")
    void pools_areWrapped_routerIsNot(
            @Autowired javax.sql.DataSource router,
            @Autowired @org.springframework.beans.factory.annotation.Qualifier("masterDataSource")
                    javax.sql.DataSource master) {
        assertThat(master).isInstanceOf(net.ttddyy.dsproxy.support.ProxyDataSource.class);
        assertThat(router).isNotInstanceOf(net.ttddyy.dsproxy.support.ProxyDataSource.class);
    }

    // ---------- helpers ----------

    private void stripeAnswers(String finalStatus) {
        stripe.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(CREATE))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent("requires_confirmation"))));
        stripe.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(CONFIRM))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(intent(finalStatus))));
    }

    private static String intent(String status) {
        return "{\"id\":\"pi_traced\",\"object\":\"payment_intent\",\"status\":\"" + status + "\"}";
    }

    private ResponseEntity<String> send(String path, String token, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String id(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody()).get("id").asText();
        } catch (java.io.IOException | NullPointerException e) {
            throw new IllegalStateException(String.valueOf(response.getBody()), e);
        }
    }

    private static List<SpanData> inTrace(List<SpanData> spans, String traceId) {
        return spans.stream().filter(s -> s.getTraceId().equals(traceId)).toList();
    }

    private static List<SpanData> named(List<SpanData> spans, String name) {
        return spans.stream().filter(s -> s.getName().equals(name)).toList();
    }

    private List<SpanData> consumerSpans(String queueFragment) {
        return spans().stream()
                .filter(s -> s.getKind() == SpanKind.CONSUMER)
                .filter(s -> (s.getName() + s.getAttributes()).contains(queueFragment))
                .toList();
    }

    /**
     * The JDBC observation names its spans {@code query} and tags the statement {@code jdbc.query}.
     */
    private static boolean isJdbc(SpanData span) {
        return span.getName().equals("query")
                || span.getAttributes().asMap().keySet().stream()
                        .anyMatch(k -> k.getKey().startsWith("jdbc."));
    }

    /** Everything a span could send to Tempo, as text: name, attributes, events, status. */
    private static String everything(SpanData span) {
        return span.getName()
                + " "
                + span.getAttributes()
                + " "
                + span.getEvents().stream()
                        .map(e -> e.getName() + " " + e.getAttributes())
                        .collect(Collectors.joining(" "))
                + " "
                + span.getStatus().getDescription();
    }

    private static void assertNothingSensitive(List<SpanData> spans, String... forbidden) {
        for (var span : spans) {
            var text = everything(span);
            for (var secret : forbidden) {
                assertThat(text)
                        .as("span '%s' must not carry '%s'", span.getName(), secret)
                        .doesNotContain(secret);
            }
            // An exception's class may travel; its message and stack trace carry the values.
            span.getEvents().stream()
                    .filter(e -> e.getName().equals("exception"))
                    .forEach(
                            e ->
                                    assertThat(
                                                    e.getAttributes().asMap().keySet().stream()
                                                            .map(k -> k.getKey()))
                                            .as("the exception event of '%s'", span.getName())
                                            .doesNotContain(
                                                    "exception.message", "exception.stacktrace"));
        }
    }

    private static String summary(List<SpanData> spans) {
        return spans.stream()
                .map(
                        s ->
                                "%s %s trace=%s span=%s parent=%s attrs=%s"
                                        .formatted(
                                                s.getKind(),
                                                s.getName(),
                                                s.getTraceId().substring(0, 8),
                                                s.getSpanId().substring(0, 6),
                                                s.getParentSpanId().substring(0, 6),
                                                abbreviate(s.getAttributes().toString())))
                .collect(Collectors.joining("\n"));
    }

    private static String abbreviate(String text) {
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }
}
